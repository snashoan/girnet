#!/usr/bin/env python3
# 
# QR Code generator library (Python)
# 
# Copyright (c) Project Nayuki. (MIT License)
# https://www.nayuki.io/page/qr-code-generator-library
# 
# Permission is hereby granted, free of charge, to any person obtaining a copy of
# this software and associated documentation files (the "Software"), to deal in
# the Software without restriction, including without limitation the rights to
# use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
# the Software, and to permit persons to whom the Software is furnished to do so,
# subject to the following conditions:
# - The above copyright notice and this permission notice shall be included in
#   all copies or substantial portions of the Software.
# - The Software is provided "as is", without warranty of any kind, express or
#   implied, including but not limited to the warranties of merchantability,
#   fitness for a particular purpose and noninfringement. In no event shall the
#   authors or copyright holders be liable for any claim, damages or other
#   liability, whether in an action of contract, tort or otherwise, arising from,
#   out of or in connection with the Software or the use or other dealings in the
#   Software.
# 

from __future__ import annotations
import collections, itertools, re
from collections.abc import Sequence
from typing import Optional, Union


# ---- QR Code symbol class ----

class QrCode:
	"""A QR Code symbol, which is a type of two-dimension barcode.
	Invented by Denso Wave and described in the ISO/IEC 18004 standard.
	Instances of this class represent an immutable square grid of dark and light cells.
	The class provides static factory functions to create a QR Code from text or binary data.
	The class covers the QR Code Model 2 specification, supporting all versions (sizes)
	from 1 to 40, all 4 error correction levels, and 4 character encoding modes.
	
	Ways to create a QR Code object:
	- High level: Take the payload data and call QrCode.encode_text() or QrCode.encode_binary().
	- Mid level: Custom-make the list of segments and call QrCode.encode_segments().
	- Low level: Custom-make the array of data codeword bytes (including
	  segment headers and final padding, excluding error correction codewords),
	  supply the appropriate version number, and call the QrCode() constructor.
	(Note that all ways require supplying the desired error correction level.)"""
	
	# ---- Static factory functions (high level) ----
	
	@staticmethod
	def encode_text(text: str, ecl: QrCode.Ecc) -> QrCode:
		"""Returns a QR Code representing the given Unicode text string at the given error correction level.
		As a conservative upper bound, this function is guaranteed to succeed for strings that have 738 or fewer
		Unicode code points (not UTF-16 code units) if the low error correction level is used. The smallest possible
		QR Code version is automatically chosen for the output. The ECC level of the result may be higher than the
		ecl argument if it can be done without increasing the version."""
		segs: list[QrSegment] = QrSegment.make_segments(text)
		return QrCode.encode_segments(segs, ecl)
	
	
	@staticmethod
	def encode_binary(data: Union[bytes,Sequence[int]], ecl: QrCode.Ecc) -> QrCode:
		"""Returns a QR Code representing the given binary data at the given error correction level.
		This function always encodes using the binary segment mode, not any text mode. The maximum number of
		bytes allowed is 2953. The smallest possible QR Code version is automatically chosen for the output.
		The ECC level of the result may be higher than the ecl argument if it can be done without increasing the version."""
		return QrCode.encode_segments([QrSegment.make_bytes(data)], ecl)
	
	
	# ---- Static factory functions (mid level) ----
	
	@staticmethod
	def encode_segments(segs: Sequence[QrSegment], ecl: QrCode.Ecc, minversion: int = 1, maxversion: int = 40, mask: int = -1, boostecl: bool = True) -> QrCode:
		"""Returns a QR Code representing the given segments with the given encoding parameters.
		The smallest possible QR Code version within the given range is automatically
		chosen for the output. Iff boostecl is true, then the ECC level of the result
		may be higher than the ecl argument if it can be done without increasing the
		version. The mask number is either between 0 to 7 (inclusive) to force that
		mask, or -1 to automatically choose an appropriate mask (which may be slow).
		This function allows the user to create a custom sequence of segments that switches
		between modes (such as alphanumeric and byte) to encode text in less space.
		This is a mid-level API; the high-level API is encode_text() and encode_binary()."""
		
		if not (QrCode.MIN_VERSION <= minversion <= maxversion <= QrCode.MAX_VERSION) or not (-1 <= mask <= 7):
			raise ValueError("Invalid value")
		
		# Find the minimal version number to use
		for version in range(minversion, maxversion + 1):
			datacapacitybits: int = QrCode._get_num_data_codewords(version, ecl) * 8  # Number of data bits available
			datausedbits: Optional[int] = QrSegment.get_total_bits(segs, version)
			if (datausedbits is not None) and (datausedbits <= datacapacitybits):
				break  # This version number is found to be suitable
			if version >= maxversion:  # All versions in the range could not fit the given data
				msg: str = "Segment too long"
				if datausedbits is not None:
					msg = f"Data length = {datausedbits} bits, Max capacity = {datacapacitybits} bits"
				raise DataTooLongError(msg)
		assert datausedbits is not None
		
		# Increase the error correction level while the data still fits in the current version number
		for newecl in (QrCode.Ecc.MEDIUM, QrCode.Ecc.QUARTILE, QrCode.Ecc.HIGH):  # From low to high
			if boostecl and (datausedbits <= QrCode._get_num_data_codewords(version, newecl) * 8):
				ecl = newecl
		
		# Concatenate all segments to create the data bit string
		bb = _BitBuffer()
		for seg in segs:
			bb.append_bits(seg.get_mode().get_mode_bits(), 4)
			bb.append_bits(seg.get_num_chars(), seg.get_mode().num_char_count_bits(version))
			bb.extend(seg._bitdata)
		assert len(bb) == datausedbits
		
		# Add terminator and pad up to a byte if applicable
		datacapacitybits = QrCode._get_num_data_codewords(version, ecl) * 8
		assert len(bb) <= datacapacitybits
		bb.append_bits(0, min(4, datacapacitybits - len(bb)))
		bb.append_bits(0, -len(bb) % 8)  # Note: Python's modulo on negative numbers behaves better than C family languages
		assert len(bb) % 8 == 0
		
		# Pad with alternating bytes until data capacity is reached
		for padbyte in itertools.cycle((0xEC, 0x11)):
			if len(bb) >= datacapacitybits:
				break
			bb.append_bits(padbyte, 8)
		
		# Pack bits into bytes in big endian
		datacodewords = bytearray([0] * (len(bb) // 8))
		for (i, bit) in enumerate(bb):
			datacodewords[i >> 3] |= bit << (7 - (i & 7))
		
		# Create the QR Code object
		return QrCode(version, ecl, datacodewords, mask)
	
	
	# ---- Private fields ----
	
	# The version number of this QR Code, which is between 1 and 40 (inclusive).
	# This determines the size of this barcode.
	_version: int
	
	# The width and height of this QR Code, measured in modules, between
	# 21 and 177 (inclusive). This is equal to version * 4 + 17.
	_size: int
	
	# The error correction level used in this QR Code.
	_errcorlvl: QrCode.Ecc
	
	# The index of the mask pattern used in this QR Code, which is between 0 and 7 (inclusive).
	# Even if a QR Code is created with automatic masking requested (mask = -1),
	# the resulting object still has a mask value between 0 and 7.
	_mask: int
	
	# The modules of this QR Code (False = light, True = dark).
	# Immutable after constructor finishes. Accessed through get_module().
	_modules: list[list[bool]]
	
	# Indicates function modules that are not subjected to masking. Discarded when constructor finishes.
	_isfunction: list[list[bool]]
	
	
	# ---- Constructor (low level) ----
	
	def __init__(self, version: int, errcorlvl: QrCode.Ecc, datacodewords: Union[bytes,Sequence[int]], msk: int) -> None:
		"""Creates a new QR Code with the given version number,
		error correction level, data codeword bytes, and mask number.
		This is a low-level API that most users should not use directly.
		A mid-level API is the encode_segments() function."""
		
		# Check scalar arguments and set fields
		if not (QrCode.MIN_VERSION <= version <= QrCode.MAX_VERSION):
			raise ValueError("Version value out of range")
		if not (-1 <= msk <= 7):
			raise ValueError("Mask value out of range")
		
		self._version = version
		self._size = version * 4 + 17
		self._errcorlvl = errcorlvl
		
		# Initialize both grids to be size*size arrays of Boolean false
		self._modules    = [[False] * self._size for _ in range(self._size)]  # Initially all light
		self._isfunction = [[False] * self._size for _ in range(self._size)]
		
		# Compute ECC, draw modules
		self._draw_function_patterns()
		allcodewords: bytes = self._add_ecc_and_interleave(bytearray(datacodewords))
		self._draw_codewords(allcodewords)
		
		# Do masking
		if msk == -1:  # Automatically choose best mask
			minpenalty: int = 1 << 32
			for i in range(8):
				self._apply_mask(i)
				self._draw_format_bits(i)
				penalty = self._get_penalty_score()
				if penalty < minpenalty:
					msk = i
					minpenalty = penalty
				self._apply_mask(i)  # Undoes the mask due to XOR
		assert 0 <= msk <= 7
		self._mask = msk
		self._apply_mask(msk)  # Apply the final choice of mask
		self._draw_format_bits(msk)  # Overwrite old format bits
		
		del self._isfunction
	
	
	# ---- Accessor methods ----
	
	def get_version(self) -> int:
		"""Returns this QR Code's version number, in the range [1, 40]."""
		return self._version
	
	def get_size(self) -> int:
		"""Returns this QR Code's size, in the range [21, 177]."""
		return self._size
	
	def get_error_correction_level(self) -> QrCode.Ecc:
		"""Returns this QR Code's error correction level."""
		return self._errcorlvl
	
	def get_mask(self) -> int:
		"""Returns this QR Code's mask, in the range [0, 7]."""
		return self._mask
	
	def get_module(self, x: int, y: int) -> bool:
		"""Returns the color of the module (pixel) at the given coordinates, which is False
		for light or True for dark. The top left corner has the coordinates (x=0, y=0).
		If the given coordinates are out of bounds, then False (light) is returned."""
		return (0 <= x < self._size) and (0 <= y < self._size) and self._modules[y][x]
	
	
	# ---- Private helper methods for constructor: Drawing function modules ----
	
	def _draw_function_patterns(self) -> None:
		"""Reads this object's version field, and draws and marks all function modules."""
		# Draw horizontal and vertical timing patterns
		for i in range(self._size):
			self._set_function_module(6, i, i % 2 == 0)
			self._set_function_module(i, 6, i % 2 == 0)
		
		# Draw 3 finder patterns (all corners except bottom right; overwrites some timing modules)
		self._draw_finder_pattern(3, 3)
		self._draw_finder_pattern(self._size - 4, 3)
		self._draw_finder_pattern(3, self._size - 4)
		
		# Draw numerous alignment patterns
		alignpatpos: list[int] = self._get_alignment_pattern_positions()
		numalign: int = len(alignpatpos)
		skips: Sequence[tuple[int,int]] = ((0, 0), (0, numalign - 1), (numalign - 1, 0))
		for i in range(numalign):
			for j in range(numalign):
				if (i, j) not in skips:  # Don't draw on the three finder corners
					self._draw_alignment_pattern(alignpatpos[i], alignpatpos[j])
		
		# Draw configuration data
		self._draw_format_bits(0)  # Dummy mask value; overwritten later in the constructor
		self._draw_version()
	
	
	def _draw_format_bits(self, mask: int) -> None:
		"""Draws two copies of the format bits (with its own error correction code)
		based on the given mask and this object's error correction level field."""
		# Calculate error correction code and pack bits
		data: int = self._errcorlvl.formatbits << 3 | mask  # errCorrLvl is uint2, mask is uint3
		rem: int = data
		for _ in range(10):
			rem = (rem << 1) ^ ((rem >> 9) * 0x537)
		bits: int = (data << 10 | rem) ^ 0x5412  # uint15
		assert bits >> 15 == 0
		
		# Draw first copy
		for i in range(0, 6):
			self._set_function_module(8, i, _get_bit(bits, i))
		self._set_function_module(8, 7, _get_bit(bits, 6))
		self._set_function_module(8, 8, _get_bit(bits, 7))
		self._set_function_module(7, 8, _get_bit(bits, 8))
		for i in range(9, 15):
			self._set_function_module(14 - i, 8, _get_bit(bits, i))
		
		# Draw second copy
		for i in range(0, 8):
			self._set_function_module(self._size - 1 - i, 8, _get_bit(bits, i))
		for i in range(8, 15):
			self._set_function_module(8, self._size - 15 + i, _get_bit(bits, i))
		self._set_function_module(8, self._size - 8, True)  # Always dark
	
	
	def _draw_version(self) -> None:
		"""Draws two copies of the version bits (with its own error correction code),
		based on this object's version field, iff 7 <= version <= 40."""
		if self._version < 7:
			return
		
		# Calculate error correction code and pack bits
		rem: int = self._version  # version is uint6, in the range [7, 40]
		for _ in range(12):
			rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
		bits: int = self._version << 12 | rem  # uint18
		assert bits >> 18 == 0
		
		# Draw two copies
		for i in range(18):
			bit: bool = _get_bit(bits, i)
			a: int = self._size - 11 + i % 3
			b: int = i // 3
			self._set_function_module(a, b, bit)
			self._set_function_module(b, a, bit)
	
	
	def _draw_finder_pattern(self, x: int, y: int) -> None:
		"""Draws a 9*9 finder pattern including the border separator,
		with the center module at (x, y). Modules can be out of bounds."""
		for dy in range(-4, 5):
			for dx in range(-4, 5):
				xx, yy = x + dx, y + dy
				if (0 <= xx < self._size) and (0 <= yy < self._size):
					# Chebyshev/infinity norm
					self._set_function_module(xx, yy, max(abs(dx), abs(dy)) not in (2, 4))
	
	
	def _draw_alignment_pattern(self, x: int, y: int) -> None:
		"""Draws a 5*5 alignment pattern, with the center module
		at (x, y). All modules must be in bounds."""
		for dy in range(-2, 3):
			for dx in range(-2, 3):
				self._set_function_module(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)
	
	
	def _set_function_module(self, x: int, y: int, isdark: bool) -> None:
		"""Sets the color of a module and marks it as a function module.
		Only used by the constructor. Coordinates must be in bounds."""
		assert type(isdark) is bool
		self._modules[y][x] = isdark
		self._isfunction[y][x] = True
	
	
	# ---- Private helper methods for constructor: Codewords and masking ----
	
	def _add_ecc_and_interleave(self, data: bytearray) -> bytes:
		"""Returns a new byte string representing the given data with the appropriate error correction
		codewords appended to it, based on this object's version and error correction level."""
		version: int = self._version
		assert len(data) == QrCode._get_num_data_codewords(version, self._errcorlvl)
		
		# Calculate parameter numbers
		numblocks: int = QrCode._NUM_ERROR_CORRECTION_BLOCKS[self._errcorlvl.ordinal][version]
		blockecclen: int = QrCode._ECC_CODEWORDS_PER_BLOCK  [self._errcorlvl.ordinal][version]
		rawcodewords: int = QrCode._get_num_raw_data_modules(version) // 8
		numshortblocks: int = numblocks - rawcodewords % numblocks
		shortblocklen: int = rawcodewords // numblocks
		
		# Split data into blocks and append ECC to each block
		blocks: list[bytes] = []
		rsdiv: bytes = QrCode._reed_solomon_compute_divisor(blockecclen)
		k: int = 0
		for i in range(numblocks):
			dat: bytearray = data[k : k + shortblocklen - blockecclen + (0 if i < numshortblocks else 1)]
			k += len(dat)
			ecc: bytes = QrCode._reed_solomon_compute_remainder(dat, rsdiv)
			if i < numshortblocks:
				dat.append(0)
			blocks.append(dat + ecc)
		assert k == len(data)
		
		# Interleave (not concatenate) the bytes from every block into a single sequence
		result = bytearray()
		for i in range(len(blocks[0])):
			for (j, blk) in enumerate(blocks):
				# Skip the padding byte in short blocks
				if (i != shortblocklen - blockecclen) or (j >= numshortblocks):
					result.append(blk[i])
		assert len(result) == rawcodewords
		return result
	
	
	def _draw_codewords(self, data: bytes) -> None:
		"""Draws the given sequence of 8-bit codewords (data and error correction) onto the entire
		data area of this QR Code. Function modules need to be marked off before this is called."""
		assert len(data) == QrCode._get_num_raw_data_modules(self._version) // 8
		
		i: int = 0  # Bit index into the data
		# Do the funny zigzag scan
		for right in range(self._size - 1, 0, -2):  # Index of right column in each column pair
			if right <= 6:
				right -= 1
			for vert in range(self._size):  # Vertical counter
				for j in range(2):
					x: int = right - j  # Actual x coordinate
					upward: bool = (right + 1) & 2 == 0
					y: int = (self._size - 1 - vert) if upward else vert  # Actual y coordinate
					if (not self._isfunction[y][x]) and (i < len(data) * 8):
						self._modules[y][x] = _get_bit(data[i >> 3], 7 - (i & 7))
						i += 1
					# If this QR Code has any remainder bits (0 to 7), they were assigned as
					# 0/false/light by the constructor and are left unchanged by this method
		assert i == len(data) * 8
	
	
	def _apply_mask(self, mask: int) -> None:
		"""XORs the codeword modules in this QR Code with the given mask pattern.
		The function modules must be marked and the codeword bits must be drawn
		before masking. Due to the arithmetic of XOR, calling _apply_mask() with
		the same mask value a second time will undo the mask. A final well-formed
		QR Code needs exactly one (not zero, two, etc.) mask applied."""
		if not (0 <= mask <= 7):
			raise ValueError("Mask value out of range")
		masker: collections.abc.Callable[[int,int],int] = QrCode._MASK_PATTERNS[mask]
		for y in range(self._size):
			for x in range(self._size):
				self._modules[y][x] ^= (masker(x, y) == 0) and (not self._isfunction[y][x])
	
	
	def _get_penalty_score(self) -> int:
		"""Calculates and returns the penalty score based on state of this QR Code's current modules.
		This is used by the automatic mask choice algorithm to find the mask pattern that yields the lowest score."""
		result: int = 0
		size: int = self._size
		modules: list[list[bool]] = self._modules
		
		# Adjacent modules in row having same color, and finder-like patterns
		for y in range(size):
			runcolor: bool = False
			runx: int = 0
			runhistory = collections.deque([0] * 7, 7)
			for x in range(size):
				if modules[y][x] == runcolor:
					runx += 1
					if runx == 5:
						result += QrCode._PENALTY_N1
					elif runx > 5:
						result += 1
				else:
					self._finder_penalty_add_history(runx, runhistory)
					if not runcolor:
						result += self._finder_penalty_count_patterns(runhistory) * QrCode._PENALTY_N3
					runcolor = modules[y][x]
					runx = 1
			result += self._finder_penalty_terminate_and_count(runcolor, runx, runhistory) * QrCode._PENALTY_N3
		# Adjacent modules in column having same color, and finder-like patterns
		for x in range(size):
			runcolor = False
			runy: int = 0
			runhistory = collections.deque([0] * 7, 7)
			for y in range(size):
				if modules[y][x] == runcolor:
					runy += 1
					if runy == 5:
						result += QrCode._PENALTY_N1
					elif runy > 5:
						result += 1
				else:
					self._finder_penalty_add_history(runy, runhistory)
					if not runcolor:
						result += self._finder_penalty_count_patterns(runhistory) * QrCode._PENALTY_N3
					runcolor = modules[y][x]
					runy = 1
			result += self._finder_penalty_terminate_and_count(runcolor, runy, runhistory) * QrCode._PENALTY_N3
		
		# 2*2 blocks of modules having same color
		for y in range(size - 1):
			for x in range(size - 1):
				if modules[y][x] == modules[y][x + 1] == modules[y + 1][x] == modules[y + 1][x + 1]:
					result += QrCode._PENALTY_N2
		
		# Balance of dark and light modules
		dark: int = sum((1 if cell else 0) for row in modules for cell in row)
		total: int = size**2  # Note that size is odd, so dark/total != 1/2
		# Compute the smallest integer k >= 0 such that (45-5k)% <= dark/total <= (55+5k)%
		k: int = (abs(dark * 20 - total * 10) + total - 1) // total - 1
		assert 0 <= k <= 9
		result += k * QrCode._PENALTY_N4
		assert 0 <= result <= 2568888  # Non-tight upper bound based on default values of PENALTY_N1, ..., N4
		return result
	
	
	# ---- Private helper functions ----
	
	def _get_alignment_pattern_positions(self) -> list[int]:
		"""Returns an ascending list of positions of alignment patterns for this version number.
		Each position is in the range [0,177), and are used on both the x and y axes.
		This could be implemented as lookup table of 40 variable-length lists of integers."""
		if self._version == 1:
			return []
		else:
			numalign: int = self._version // 7 + 2
			step: int = (self._version * 8 + numalign * 3 + 5) // (numalign * 4 - 4) * 2
			result: list[int] = [(self._size - 7 - i * step) for i in range(numalign - 1)] + [6]
			return list(reversed(result))
	
	
	@staticmethod
	def _get_num_raw_data_modules(ver: int) -> int:
		"""Returns the number of data bits that can be stored in a QR Code of the given version number, after
		all function modules are excluded. This includes remainder bits, so it might not be a multiple of 8.
		The result is in the range [208, 29648]. This could be implemented as a 40-entry lookup table."""
		if not (QrCode.MIN_VERSION <= ver <= QrCode.MAX_VERSION):
			raise ValueError("Version number out of range")
		result: int = (16 * ver + 128) * ver + 64
		if ver >= 2:
			numalign: int = ver // 7 + 2
			result -= (25 * numalign - 10) * numalign - 55
			if ver >= 7:
				result -= 36
		assert 208 <= result <= 29648
		return result
	
	
	@staticmethod
	def _get_num_data_codewords(ver: int, ecl: QrCode.Ecc) -> int:
		"""Returns the number of 8-bit data (i.e. not error correction) codewords contained in any
		QR Code of the given version number and error correction level, with remainder bits discarded.
		This stateless pure function could be implemented as a (40*4)-cell lookup table."""
		return QrCode._get_num_raw_data_modules(ver) // 8 \
			- QrCode._ECC_CODEWORDS_PER_BLOCK    [ecl.ordinal][ver] \
			* QrCode._NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver]
	
	
	@staticmethod
	def _reed_solomon_compute_divisor(degree: int) -> bytes:
		"""Returns a Reed-Solomon ECC generator polynomial for the given degree. This could be
		implemented as a lookup table over all possible parameter values, instead of as an algorithm."""
		if not (1 <= degree <= 255):
			raise ValueError("Degree out of range")
		# Polynomial coefficients are stored from highest to lowest power, excluding the leading term which is always 1.
		# For example the polynomial x^3 + 255x^2 + 8x + 93 is stored as the uint8 array [255, 8, 93].
		result = bytearray([0] * (degree - 1) + [1])  # Start off with the monomial x^0
		
		# Compute the product polynomial (x - r^0) * (x - r^1) * (x - r^2) * ... * (x - r^{degree-1}),
		# and drop the highest monomial term which is always 1x^degree.
		# Note that r = 0x02, which is a generator element of this field GF(2^8/0x11D).
		root: int = 1
		for _ in range(degree):  # Unused variable i
			# Multiply the current product by (x - r^i)
			for j in range(degree):
				result[j] = QrCode._reed_solomon_multiply(result[j], root)
				if j + 1 < degree:
					result[j] ^= result[j + 1]
			root = QrCode._reed_solomon_multiply(root, 0x02)
		return result
	
	
	@staticmethod
	def _reed_solomon_compute_remainder(data: bytes, divisor: bytes) -> bytes:
		"""Returns the Reed-Solomon error correction codeword for the given data and divisor polynomials."""
		result = bytearray([0] * len(divisor))
		for b in data:  # Polynomial division
			factor: int = b ^ result.pop(0)
			result.append(0)
			for (i, coef) in enumerate(divisor):
				result[i] ^= QrCode._reed_solomon_multiply(coef, factor)
		return result
	
	
	@staticmethod
	def _reed_solomon_multiply(x: int, y: int) -> int:
		"""Returns the product of the two given field elements modulo GF(2^8/0x11D). The arguments and result
		are unsigned 8-bit integers. This could be implemented as a lookup table of 256*256 entries of uint8."""
		if (x >> 8 != 0) or (y >> 8 != 0):
			raise ValueError("Byte out of range")
		# Russian peasant multiplication
		z: int = 0
		for i in reversed(range(8)):
			z = (z << 1) ^ ((z >> 7) * 0x11D)
			z ^= ((y >> i) & 1) * x
		assert z >> 8 == 0
		return z
	
	
	def _finder_penalty_count_patterns(self, runhistory: collections.deque[int]) -> int:
		"""Can only be called immediately after a light run is added, and
		returns either 0, 1, or 2. A helper function for _get_penalty_score()."""
		n: int = runhistory[1]
		assert n <= self._size * 3
		core: bool = n > 0 and (runhistory[2] == runhistory[4] == runhistory[5] == n) and runhistory[3] == n * 3
		return (1 if (core and runhistory[0] >= n * 4 and runhistory[6] >= n) else 0) \
		     + (1 if (core and runhistory[6] >= n * 4 and runhistory[0] >= n) else 0)
	
	
	def _finder_penalty_terminate_and_count(self, currentruncolor: bool, currentrunlength: int, runhistory: collections.deque[int]) -> int:
		"""Must be called at the end of a line (row or column) of modules. A helper function for _get_penalty_score()."""
		if currentruncolor:  # Terminate dark run
			self._finder_penalty_add_history(currentrunlength, runhistory)
			currentrunlength = 0
		currentrunlength += self._size  # Add light border to final run
		self._finder_penalty_add_history(currentrunlength, runhistory)
		return self._finder_penalty_count_patterns(runhistory)
	
	
	def _finder_penalty_add_history(self, currentrunlength: int, runhistory: collections.deque[int]) -> None:
		if runhistory[0] == 0:
			currentrunlength += self._size  # Add light border to initial run
		runhistory.appendleft(currentrunlength)
	
	
	# ---- Constants and tables ----
	
	MIN_VERSION: int =  1  # The minimum version number supported in the QR Code Model 2 standard
	MAX_VERSION: int = 40  # The maximum version number supported in the QR Code Model 2 standard
	
	# For use in _get_penalty_score(), when evaluating which mask is best.
	_PENALTY_N1: int =  3
	_PENALTY_N2: int =  3
	_PENALTY_N3: int = 40
	_PENALTY_N4: int = 10
	
	_ECC_CODEWORDS_PER_BLOCK: Sequence[Sequence[int]] = (
		# Version: (note that index 0 is for padding, and is set to an illegal value)
		# 0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40    Error correction level
		(-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),  # Low
		(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),  # Medium
		(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),  # Quartile
		(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30))  # High
	
	_NUM_ERROR_CORRECTION_BLOCKS: Sequence[Sequence[int]] = (
		# Version: (note that index 0 is for padding, and is set to an illegal value)
		# 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40    Error correction level
		(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),  # Low
		(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),  # Medium
		(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),  # Quartile
		(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81))  # High
	
	_MASK_PATTERNS: Sequence[collections.abc.Callable[[int,int],int]] = (
		(lambda x, y:  (x + y) % 2                  ),
		(lambda x, y:  y % 2                        ),
		(lambda x, y:  x % 3                        ),
		(lambda x, y:  (x + y) % 3                  ),
		(lambda x, y:  (x // 3 + y // 2) % 2        ),
		(lambda x, y:  x * y % 2 + x * y % 3        ),
		(lambda x, y:  (x * y % 2 + x * y % 3) % 2  ),
		(lambda x, y:  ((x + y) % 2 + x * y % 3) % 2),
	)
	
	
	# ---- Public helper enumeration ----
	
	class Ecc:
		ordinal: int  # (Public) In the range 0 to 3 (unsigned 2-bit integer)
		formatbits: int  # (Package-private) In the range 0 to 3 (unsigned 2-bit integer)
		
		"""The error correction level in a QR Code symbol. Immutable."""
		# Private constructor
		def __init__(self, i: int, fb: int) -> None:
			self.ordinal = i
			self.formatbits = fb
		
		# Placeholders
		LOW     : QrCode.Ecc
		MEDIUM  : QrCode.Ecc
		QUARTILE: QrCode.Ecc
		HIGH    : QrCode.Ecc
	
	# Public constants. Create them outside the class.
	Ecc.LOW      = Ecc(0, 1)  # The QR Code can tolerate about  7% erroneous codewords
	Ecc.MEDIUM   = Ecc(1, 0)  # The QR Code can tolerate about 15% erroneous codewords
	Ecc.QUARTILE = Ecc(2, 3)  # The QR Code can tolerate about 25% erroneous codewords
	Ecc.HIGH     = Ecc(3, 2)  # The QR Code can tolerate about 30% erroneous codewords



# ---- Data segment class ----

class QrSegment:
	"""A segment of character/binary/control data in a QR Code symbol.
	Instances of this class are immutable.
	The mid-level way to create a segment is to take the payload data
	and call a static factory function such as QrSegment.make_numeric().
	The low-level way to create a segment is to custom-make the bit buffer
	and call the QrSegment() constructor with appropriate values.
	This segment class imposes no length restrictions, but QR Codes have restrictions.
	Even in the most favorable conditions, a QR Code can only hold 7089 characters of data.
	Any segment longer than this is meaningless for the purpose of generating QR Codes."""
	
	# ---- Static factory functions (mid level) ----
	
	@staticmethod
	def make_bytes(data: Union[bytes,Sequence[int]]) -> QrSegment:
		"""Returns a segment representing the given binary data encoded in byte mode.
		All input byte lists are acceptable. Any text string can be converted to
		UTF-8 bytes (s.encode("UTF-8")) and encoded as a byte mode segment."""
		bb = _BitBuffer()
		for b in data:
			bb.append_bits(b, 8)
		return QrSegment(QrSegment.Mode.BYTE, len(data), bb)
	
	
	@staticmethod
	def make_numeric(digits: str) -> QrSegment:
		"""Returns a segment representing the given string of decimal digits encoded in numeric mode."""
		if not QrSegment.is_numeric(digits):
			raise ValueError("String contains non-numeric characters")
		bb = _BitBuffer()
		i: int = 0
		while i < len(digits):  # Consume up to 3 digits per iteration
			n: int = min(len(digits) - i, 3)
			bb.append_bits(int(digits[i : i + n]), n * 3 + 1)
			i += n
		return QrSegment(QrSegment.Mode.NUMERIC, len(digits), bb)
	
	
	@staticmethod
	def make_alphanumeric(text: str) -> QrSegment:
		"""Returns a segment representing the given text string encoded in alphanumeric mode.
		The characters allowed are: 0 to 9, A to Z (uppercase only), space,
		dollar, percent, asterisk, plus, hyphen, period, slash, colon."""
		if not QrSegment.is_alphanumeric(text):
			raise ValueError("String contains unencodable characters in alphanumeric mode")
		bb = _BitBuffer()
		for i in range(0, len(text) - 1, 2):  # Process groups of 2
			temp: int = QrSegment._ALPHANUMERIC_ENCODING_TABLE[text[i]] * 45
			temp += QrSegment._ALPHANUMERIC_ENCODING_TABLE[text[i + 1]]
			bb.append_bits(temp, 11)
		if len(text) % 2 > 0:  # 1 character remaining
			bb.append_bits(QrSegment._ALPHANUMERIC_ENCODING_TABLE[text[-1]], 6)
		return QrSegment(QrSegment.Mode.ALPHANUMERIC, len(text), bb)
	
	
	@staticmethod
	def make_segments(text: str) -> list[QrSegment]:
		"""Returns a new mutable list of zero or more segments to represent the given Unicode text string.
		The result may use various segment modes and switch modes to optimize the length of the bit stream."""
		
		# Select the most efficient segment encoding automatically
		if text == "":
			return []
		elif QrSegment.is_numeric(text):
			return [QrSegment.make_numeric(text)]
		elif QrSegment.is_alphanumeric(text):
			return [QrSegment.make_alphanumeric(text)]
		else:
			return [QrSegment.make_bytes(text.encode("UTF-8"))]
	
	
	@staticmethod
	def make_eci(assignval: int) -> QrSegment:
		"""Returns a segment representing an Extended Channel Interpretation
		(ECI) designator with the given assignment value."""
		bb = _BitBuffer()
		if assignval < 0:
			raise ValueError("ECI assignment value out of range")
		elif assignval < (1 << 7):
			bb.append_bits(assignval, 8)
		elif assignval < (1 << 14):
			bb.append_bits(0b10, 2)
			bb.append_bits(assignval, 14)
		elif assignval < 1000000:
			bb.append_bits(0b110, 3)
			bb.append_bits(assignval, 21)
		else:
			raise ValueError("ECI assignment value out of range")
		return QrSegment(QrSegment.Mode.ECI, 0, bb)
	
	
	# Tests whether the given string can be encoded as a segment in numeric mode.
	# A string is encodable iff each character is in the range 0 to 9.
	@staticmethod
	def is_numeric(text: str) -> bool:
		return QrSegment._NUMERIC_REGEX.fullmatch(text) is not None
	
	
	# Tests whether the given string can be encoded as a segment in alphanumeric mode.
	# A string is encodable iff each character is in the following set: 0 to 9, A to Z
	# (uppercase only), space, dollar, percent, asterisk, plus, hyphen, period, slash, colon.
	@staticmethod
	def is_alphanumeric(text: str) -> bool:
		return QrSegment._ALPHANUMERIC_REGEX.fullmatch(text) is not None
	
	
	# ---- Private fields ----
	
	# The mode indicator of this segment. Accessed through get_mode().
	_mode: QrSegment.Mode
	
	# The length of this segment's unencoded data. Measured in characters for
	# numeric/alphanumeric/kanji mode, bytes for byte mode, and 0 for ECI mode.
	# Always zero or positive. Not the same as the data's bit length.
	# Accessed through get_num_chars().
	_numchars: int
	
	# The data bits of this segment. Accessed through get_data().
	_bitdata: list[int]
	
	
	# ---- Constructor (low level) ----
	
	def __init__(self, mode: QrSegment.Mode, numch: int, bitdata: Sequence[int]) -> None:
		"""Creates a new QR Code segment with the given attributes and data.
		The character count (numch) must agree with the mode and the bit buffer length,
		but the constraint isn't checked. The given bit buffer is cloned and stored."""
		if numch < 0:
			raise ValueError()
		self._mode = mode
		self._numchars = numch
		self._bitdata = list(bitdata)  # Make defensive copy
	
	
	# ---- Accessor methods ----
	
	def get_mode(self) -> QrSegment.Mode:
		"""Returns the mode field of this segment."""
		return self._mode
	
	def get_num_chars(self) -> int:
		"""Returns the character count field of this segment."""
		return self._numchars
	
	def get_data(self) -> list[int]:
		"""Returns a new copy of the data bits of this segment."""
		return list(self._bitdata)  # Make defensive copy
	
	
	# Package-private function
	@staticmethod
	def get_total_bits(segs: Sequence[QrSegment], version: int) -> Optional[int]:
		"""Calculates the number of bits needed to encode the given segments at
		the given version. Returns a non-negative number if successful. Otherwise
		returns None if a segment has too many characters to fit its length field."""
		result = 0
		for seg in segs:
			ccbits: int = seg.get_mode().num_char_count_bits(version)
			if seg.get_num_chars() >= (1 << ccbits):
				return None  # The segment's length doesn't fit the field's bit width
			result += 4 + ccbits + len(seg._bitdata)
		return result
	
	
	# ---- Constants ----
	
	# Describes precisely all strings that are encodable in numeric mode.
	_NUMERIC_REGEX: re.Pattern[str] = re.compile(r"[0-9]*")
	
	# Describes precisely all strings that are encodable in alphanumeric mode.
	_ALPHANUMERIC_REGEX: re.Pattern[str] = re.compile(r"[A-Z0-9 $%*+./:-]*")
	
	# Dictionary of "0"->0, "A"->10, "$"->37, etc.
	_ALPHANUMERIC_ENCODING_TABLE: dict[str,int] = {ch: i for (i, ch) in enumerate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:")}
	
	
	# ---- Public helper enumeration ----
	
	class Mode:
		"""Describes how a segment's data bits are interpreted. Immutable."""
		
		_modebits: int  # The mode indicator bits, which is a uint4 value (range 0 to 15)
		_charcounts: tuple[int,int,int]  # Number of character count bits for three different version ranges
		
		# Private constructor
		def __init__(self, modebits: int, charcounts: tuple[int,int,int]):
			self._modebits = modebits
			self._charcounts = charcounts
		
		# Package-private method
		def get_mode_bits(self) -> int:
			"""Returns an unsigned 4-bit integer value (range 0 to 15) representing the mode indicator bits for this mode object."""
			return self._modebits
		
		# Package-private method
		def num_char_count_bits(self, ver: int) -> int:
			"""Returns the bit width of the character count field for a segment in this mode
			in a QR Code at the given version number. The result is in the range [0, 16]."""
			return self._charcounts[(ver + 7) // 17]
		
		# Placeholders
		NUMERIC     : QrSegment.Mode
		ALPHANUMERIC: QrSegment.Mode
		BYTE        : QrSegment.Mode
		KANJI       : QrSegment.Mode
		ECI         : QrSegment.Mode
	
	# Public constants. Create them outside the class.
	Mode.NUMERIC      = Mode(0x1, (10, 12, 14))
	Mode.ALPHANUMERIC = Mode(0x2, ( 9, 11, 13))
	Mode.BYTE         = Mode(0x4, ( 8, 16, 16))
	Mode.KANJI        = Mode(0x8, ( 8, 10, 12))
	Mode.ECI          = Mode(0x7, ( 0,  0,  0))



# ---- Private helper class ----

class _BitBuffer(list[int]):
	"""An appendable sequence of bits (0s and 1s). Mainly used by QrSegment."""
	
	def append_bits(self, val: int, n: int) -> None:
		"""Appends the given number of low-order bits of the given
		value to this buffer. Requires n >= 0 and 0 <= val < 2^n."""
		if (n < 0) or (val >> n != 0):
			raise ValueError("Value out of range")
		self.extend(((val >> i) & 1) for i in reversed(range(n)))


def _get_bit(x: int, i: int) -> bool:
	"""Returns true iff the i'th bit of x is set to 1."""
	return (x >> i) & 1 != 0



class DataTooLongError(ValueError):
	"""Raised when the supplied data does not fit any QR Code version. Ways to handle this exception include:
	- Decrease the error correction level if it was greater than Ecc.LOW.
	- If the encode_segments() function was called with a maxversion argument, then increase
	  it if it was less than QrCode.MAX_VERSION. (This advice does not apply to the other
	  factory functions because they search all versions up to QrCode.MAX_VERSION.)
	- Split the text data into better or optimal segments in order to reduce the number of bits required.
	- Change the text or binary data to be shorter.
	- Change the text to fit the character set of a particular segment mode (e.g. alphanumeric).
	- Propagate the error upward to the caller/user."""
	pass


"""
shnet - Self-contained node runtime & CLI-to-CLI peer messaging (Python edition)
Zero external dependencies. Compatible with Python 3.8+.
"""

import os
import sys
import time
import json
import socket
import select
import urllib.request
import urllib.error
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
import threading
import argparse
from datetime import datetime

VERSION = "1.0.0"
DEFAULT_PORT = 8723

ANSI_RESET = "\033[0m"
ANSI_BOLD = "\033[1m"
ANSI_DIM = "\033[2m"
ANSI_ITALIC = "\033[3m"

CLR_CYAN = "\033[38;5;45m"
CLR_BLUE = "\033[38;5;75m"
CLR_GREEN = "\033[38;5;48m"
CLR_GOLD = "\033[38;5;220m"
CLR_RED = "\033[38;5;203m"
CLR_PURPLE = "\033[38;5;141m"
CLR_ORANGE = "\033[38;5;208m"
CLR_GRAY = "\033[38;5;244m"
ANSI_WHITE_BG_BLACK_FG = "\033[47;30m"

# ---------------------------------------------------------------------------
# Identity Management (~/.shnet/identity)
# ---------------------------------------------------------------------------

def get_identity_path():
    home = os.path.expanduser("~")
    d = os.path.join(home, ".shnet")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "identity")

def save_nickname(nick: str):
    if not nick or not nick.strip():
        return
    try:
        with open(get_identity_path(), "w", encoding="utf-8") as f:
            f.write(nick.strip() + "\n")
    except Exception:
        pass

def resolve_nickname(cli_name=None) -> str:
    if cli_name and cli_name.strip():
        n = cli_name.strip()
        save_nickname(n)
        return n

    id_file = get_identity_path()
    if os.path.exists(id_file):
        try:
            with open(id_file, "r", encoding="utf-8") as f:
                content = f.read().strip()
                if content:
                    return content
        except Exception:
            pass

    if sys.stdin.isatty():
        try:
            sys.stdout.write("nickname [rei]: ")
            sys.stdout.flush()
            val = sys.stdin.readline()
            if val and val.strip():
                chosen = val.strip()
                save_nickname(chosen)
                return chosen
        except Exception:
            pass

    save_nickname("rei")
    return "rei"

# ---------------------------------------------------------------------------
# Data Model & Local Storage
# ---------------------------------------------------------------------------

class ChatMessage:
    def __init__(self, id=None, sender="anonymous", recipient="", peer="", text="", timestamp=None, direction="OUT", reply_port=0):
        self.id = id or f"{int(time.time()*1000)}-{os.urandom(4).hex()}"
        self.sender = sender
        self.recipient = recipient
        self.peer = peer
        self.text = text
        self.timestamp = timestamp or int(time.time() * 1000)
        self.direction = direction
        self.reply_port = reply_port

    def to_dict(self):
        d = {
            "id": self.id,
            "sender": self.sender,
            "recipient": self.recipient,
            "peer": self.peer,
            "text": self.text,
            "timestamp": self.timestamp,
            "direction": self.direction,
        }
        if self.reply_port > 0:
            d["reply_port"] = self.reply_port
        return d

    def to_json(self):
        return json.dumps(self.to_dict())

    @classmethod
    def from_json(cls, s):
        data = json.loads(s)
        return cls(
            id=data.get("id"),
            sender=data.get("sender", "anonymous"),
            recipient=data.get("recipient", ""),
            peer=data.get("peer", ""),
            text=data.get("text", ""),
            timestamp=data.get("timestamp"),
            direction=data.get("direction", "OUT"),
            reply_port=data.get("reply_port", 0),
        )


class ChatStorage:
    def __init__(self, directory=None):
        if directory:
            self.chat_dir = directory
        else:
            self.chat_dir = os.path.expanduser("~/.shnet/chats")
        self.history_file = os.path.join(self.chat_dir, "history.jsonl")
        self._lock = threading.Lock()
        os.makedirs(self.chat_dir, exist_ok=True)

    def save(self, message: ChatMessage):
        with self._lock:
            with open(self.history_file, "a", encoding="utf-8") as f:
                f.write(message.to_json() + "\n")

    def get_history(self, peer=None, limit=50):
        if not os.path.exists(self.history_file):
            return []
        norm_peer = normalize_peer(peer) if peer else None
        results = []
        with self._lock:
            with open(self.history_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        msg = ChatMessage.from_json(line)
                        if not norm_peer or norm_peer.lower() == normalize_peer(msg.peer).lower():
                            results.append(msg)
                    except Exception:
                        pass
        return results[-limit:] if limit > 0 else results

    def get_recent_peers(self):
        if not os.path.exists(self.history_file):
            return []
        seen = []
        with self._lock:
            with open(self.history_file, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        msg = ChatMessage.from_json(line)
                        if msg.peer and msg.peer not in seen:
                            seen.append(msg.peer)
                    except Exception:
                        pass
        return list(reversed(seen))

    def clear(self, peer=None):
        if not os.path.exists(self.history_file):
            return
        with self._lock:
            if not peer:
                os.remove(self.history_file)
                return
            norm_peer = normalize_peer(peer).lower()
            kept = []
            with open(self.history_file, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        msg = ChatMessage.from_json(line)
                        if normalize_peer(msg.peer).lower() != norm_peer:
                            kept.append(line.strip())
                    except Exception:
                        pass
            with open(self.history_file, "w", encoding="utf-8") as f:
                for line in kept:
                    f.write(line + "\n")


def normalize_peer(raw):
    if not raw:
        return ""
    s = raw.strip()
    if s.startswith("http://"):
        s = s[7:]
    elif s.startswith("https://"):
        s = s[8:]
    if s.endswith("/"):
        s = s[:-1]
    return s


def is_virtual_or_container(name):
    if not name:
        return False
    lower = name.lower()
    return lower.startswith(("docker", "br-", "veth", "virbr", "cni"))


def format_peer_url(raw):
    if not raw:
        raise ValueError("Peer address cannot be empty")
    import ipaddress
    s = raw.strip()
    scheme = "http://"
    if s.startswith("http://"):
        scheme = "http://"
        s = s[7:]
    elif s.startswith("https://"):
        scheme = "https://"
        s = s[8:]
    if s.endswith("/"):
        s = s[:-1]

    if s.startswith("[") and "]" in s:
        if ":" not in s[s.find("]"): ]:
            s = f"{s}:{DEFAULT_PORT}"
        return f"{scheme}{s}/"

    # Check if pure IPv6 address without brackets
    try:
        ipaddress.IPv6Address(s)
        return f"{scheme}[{s}]:{DEFAULT_PORT}/"
    except ValueError:
        pass

    # Check if IPv6 with trailing port
    if ":" in s and s.count(":") >= 2:
        parts = s.rsplit(":", 1)
        try:
            ipaddress.IPv6Address(parts[0])
            if parts[1].isdigit() and 0 < int(parts[1]) <= 65535:
                return f"{scheme}[{parts[0]}]:{parts[1]}/"
        except ValueError:
            pass

    if ":" not in s:
        return f"{scheme}{s}:{DEFAULT_PORT}/"

    return f"{scheme}{s}/"

# ---------------------------------------------------------------------------
# Link Discovery & QR Rendering
# ---------------------------------------------------------------------------

def is_internet_ipv6_addr(host):
    import ipaddress
    try:
        obj = ipaddress.IPv6Address(host)
        return obj.is_global and not obj.is_private and not obj.is_link_local and not obj.is_loopback
    except Exception:
        return False


def get_local_links(port, scheme="http"):
    global_v6 = []
    ll_v6 = []
    v4 = []
    seen = set()

    # 1. Parse 'ip -6 -o addr show' via netlink (works on Linux and Android)
    try:
        import subprocess
        res = subprocess.run(["ip", "-6", "-o", "addr", "show"], capture_output=True, text=True, timeout=1.0)
        if res.returncode == 0:
            for line in res.stdout.splitlines():
                parts = line.strip().split()
                if len(parts) >= 6 and parts[2].lower() == "inet6":
                    iface = parts[1].rstrip(":")
                    if is_virtual_or_container(iface):
                        continue
                    cidr = parts[3]
                    host = cidr.split("/")[0]
                    scope = parts[5].lower()
                    if scope == "host" or host == "::1":
                        continue
                    is_ll = (scope == "link" or host.lower().startswith("fe80"))
                    url = f"{scheme}://[{host}]:{port}/"
                    if url not in seen:
                        seen.add(url)
                        if is_ll:
                            lbl = f"{iface} Local IPv6 (link-local)"
                            ll_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": "local-v6"})
                        else:
                            is_inet = is_internet_ipv6_addr(host)
                            badge = "internet-v6" if is_inet else "local-v6"
                            lbl = f"{iface} Internet IPv6" if is_inet else f"{iface} Local IPv6"
                            global_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": badge})
    except Exception:
        pass

    # 2. Linux /proc/net/if_inet6 fallback
    if not global_v6 and not ll_v6 and os.path.exists("/proc/net/if_inet6"):
        try:
            with open("/proc/net/if_inet6", "r") as f:
                for line in f:
                    parts = line.strip().split()
                    if len(parts) >= 6:
                        raw_ip, iface = parts[0], parts[5]
                        if raw_ip.startswith("00000000000000000000000000000001"):
                            continue
                        chunks = [raw_ip[i:i+4] for i in range(0, 32, 4)]
                        ipv6_str = ":".join(chunks)
                        try:
                            ipv6_norm = socket.inet_ntop(socket.AF_INET6, socket.inet_pton(socket.AF_INET6, ipv6_str))
                            is_ll = ipv6_norm.lower().startswith("fe80")
                            url = f"{scheme}://[{ipv6_norm}]:{port}/"
                            if url not in seen:
                                seen.add(url)
                                if is_ll:
                                    lbl = f"{iface} Local IPv6 (link-local)"
                                    ll_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": "local-v6"})
                                else:
                                    is_inet = is_internet_ipv6_addr(ipv6_norm)
                                    badge = "internet-v6" if is_inet else "local-v6"
                                    lbl = f"{iface} Internet IPv6" if is_inet else f"{iface} Local IPv6"
                                    global_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": badge})
                        except Exception:
                            pass
        except Exception:
            pass

    # 3. Default route probe for IPv4
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            url = f"{scheme}://{ip}:{port}/"
            if url not in seen:
                seen.add(url)
                v4.append({"label": "LAN IPv4", "url": url, "isIpv6": False, "badge": "local-v4"})
    except Exception:
        pass

    links = global_v6 + ll_v6 + v4
    if not links:
        links.append({"label": "localhost IPv6", "url": f"{scheme}://[::1]:{port}/", "isIpv6": True, "badge": "loopback-v6"})
        links.append({"label": "localhost IPv4", "url": f"{scheme}://127.0.0.1:{port}/", "isIpv6": False, "badge": "loopback-v4"})

    return links


def get_primary_url(port, scheme="http"):
    links = get_local_links(port, scheme)
    for l in links:
        if l.get("badge") == "internet-v6":
            return l["url"]
    return links[0]["url"] if links else f"{scheme}://[::1]:{port}/"


def render_qr_ansi(text):
    try:
        qr = QrCode.encode_text(text, QrCode.Ecc.MEDIUM)
        size = qr.get_size()
        border = 2
        total = size + border * 2

        lines = [ANSI_WHITE_BG_BLACK_FG + " " * total + ANSI_RESET]
        for y in range(0, size, 2):
            row = [ANSI_WHITE_BG_BLACK_FG, " " * border]
            for x in range(size):
                top = qr.get_module(x, y)
                bottom = qr.get_module(x, y + 1) if (y + 1 < size) else False
                if top and bottom:
                    row.append("█")
                elif not top and not bottom:
                    row.append(" ")
                elif top:
                    row.append("▀")
                else:
                    row.append("▄")
            row.append(" " * border)
            row.append(ANSI_RESET)
            lines.append("".join(row))
        lines.append(ANSI_WHITE_BG_BLACK_FG + " " * total + ANSI_RESET)
        return "\n".join(lines)
    except Exception as e:
        return f"[QR Error: {e}]"

# ---------------------------------------------------------------------------
# HTTP Server & Router
# ---------------------------------------------------------------------------

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, RequestHandlerClass, bind_v6=False):
        self.bind_v6 = bind_v6
        if bind_v6:
            self.address_family = socket.AF_INET6
        super().__init__(server_address, RequestHandlerClass)

    def server_bind(self):
        if self.bind_v6:
            try:
                # Enable dual-stack IPv6 & IPv4 before binding to wildcard ::
                self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            except Exception:
                pass
        try:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        super().server_bind()

    def get_request(self):
        sock, addr = super().get_request()
        try:
            sock.settimeout(10.0)
        except Exception:
            pass
        return sock, addr


def make_shnet_handler(storage, node_name="rei", message_callback=None, static_dir=None, connect_callback=None, disconnect_callback=None):
    class ShnetHTTPHandler(BaseHTTPRequestHandler):
        server_version = f"shnet/{VERSION}"

        def log_message(self, format, *args):
            # Suppress default noisy access logs unless debug
            pass

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/api/chat/message":
                self.handle_chat_message()
            elif parsed.path == "/api/chat/connect":
                self.handle_chat_connect()
            elif parsed.path == "/api/chat/disconnect":
                self.handle_chat_disconnect()
            else:
                self.send_error(404, "Not Found")

        def handle_chat_connect(self):
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 65536)
                body = self.rfile.read(length).decode("utf-8")
                data = json.loads(body)
                c_name = data.get("name", "anonymous")
                c_port = int(data.get("port", 0))
                client_ip = self.client_address[0]
                if client_ip.startswith("::ffff:"):
                    client_ip = client_ip[7:]
                elif client_ip in ("::1", "0:0:0:0:0:0:0:1"):
                    client_ip = "127.0.0.1"

                host_part = f"[{client_ip}]" if (":" in client_ip and not client_ip.startswith("[")) else client_ip
                peer_url = data.get("internet_url") or (f"http://{host_part}:{c_port}/" if c_port > 0 else f"http://{host_part}/")
                if connect_callback:
                    connect_callback(c_name, peer_url)
                self.send_json(200, {
                    "status": "connected",
                    "name": node_name,
                    "port": self.server.server_port,
                    "internet_url": get_primary_url(self.server.server_port),
                    "peer": peer_url
                })
            except Exception as e:
                self.send_json(400, {"error": str(e)})

        def handle_chat_disconnect(self):
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 65536)
                body = self.rfile.read(length).decode("utf-8")
                data = json.loads(body)
                c_name = data.get("name", "peer")
                if disconnect_callback:
                    disconnect_callback(c_name)
                self.send_json(200, {"status": "disconnected"})
            except Exception as e:
                self.send_json(400, {"error": str(e)})

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path

            if path == "/api/chat/info":
                self.send_json(200, {"name": node_name, "version": VERSION, "service": "shnet-chat", "port": self.server.server_port})
            elif path == "/api/chat/history":
                query = urllib.parse.parse_qs(parsed.query)
                peer = query.get("peer", [None])[0]
                limit = int(query.get("limit", [50])[0])
                msgs = storage.get_history(peer, limit)
                self.send_json(200, [m.to_dict() for m in msgs])
            elif path == "/_shnet/status":
                links = get_local_links(self.server.server_port)
                self.send_json(200, {
                    "status": "running",
                    "version": VERSION,
                    "port": self.server.server_port,
                    "endpoint": get_primary_url(self.server.server_port),
                    "links": links
                })
            elif path == "/_shnet/links":
                links = get_local_links(self.server.server_port)
                html = "<html><body><h2>shnet links</h2><ul>"
                for l in links:
                    html += f"<li><strong>{l['label']}</strong>: <a href='{l['url']}'>{l['url']}</a></li>"
                html += "</ul></body></html>"
                self.send_html(200, html)
            elif static_dir and os.path.exists(static_dir):
                self.handle_static_file(path)
            elif path == "/":
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"hello from shnet (Python)")
            else:
                self.send_error(404, "Not Found")

        def handle_chat_message(self):
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 65536)
                body = self.rfile.read(length).decode("utf-8")
                msg = ChatMessage.from_json(body)
                msg.direction = "IN"
                msg.recipient = node_name
                client_ip = self.client_address[0]
                if client_ip.startswith("::ffff:"):
                    client_ip = client_ip[7:]
                elif client_ip in ("::1", "0:0:0:0:0:0:0:1"):
                    client_ip = "127.0.0.1"

                host_part = f"[{client_ip}]" if (":" in client_ip and not client_ip.startswith("[")) else client_ip
                if msg.reply_port > 0:
                    peer_url = f"http://{host_part}:{msg.reply_port}/"
                    msg.peer = peer_url
                    if connect_callback:
                        connect_callback(msg.sender, peer_url)
                elif not msg.peer or msg.peer == "me":
                    msg.peer = f"http://{host_part}/"

                storage.save(msg)
                if message_callback:
                    message_callback(msg)

                self.send_json(200, {"status": "ok", "id": msg.id, "recipient": node_name})
            except Exception as e:
                self.send_json(400, {"error": str(e)})

        def handle_static_file(self, req_path):
            rel = req_path.lstrip("/")
            target = os.path.normpath(os.path.join(static_dir, rel))
            if not target.startswith(os.path.abspath(static_dir)):
                self.send_error(403, "Forbidden")
                return

            if os.path.isdir(target):
                index = os.path.join(target, "index.html")
                if os.path.exists(index):
                    target = index
                else:
                    self.send_dir_listing(target, req_path)
                    return

            if not os.path.exists(target):
                self.send_error(404, "Not Found")
                return

            ext = os.path.splitext(target)[1].lower()
            mime = "application/octet-stream"
            if ext in (".html", ".htm"): mime = "text/html; charset=utf-8"
            elif ext == ".css": mime = "text/css; charset=utf-8"
            elif ext in (".js", ".mjs"): mime = "application/javascript; charset=utf-8"
            elif ext == ".json": mime = "application/json; charset=utf-8"
            elif ext == ".txt": mime = "text/plain; charset=utf-8"
            elif ext == ".png": mime = "image/png"
            elif ext in (".jpg", ".jpeg"): mime = "image/jpeg"
            elif ext == ".svg": mime = "image/svg+xml"

            with open(target, "rb") as f:
                data = f.read()

            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def send_dir_listing(self, dir_path, req_path):
            entries = sorted(os.listdir(dir_path))
            html = f"<html><head><title>Index of {req_path}</title></head><body>"
            html += f"<h2>Index of {req_path}</h2><ul>"
            if req_path != "/":
                html += "<li><a href='../'>..</a></li>"
            for e in entries:
                if e.startswith("."): continue
                slash = "/" if os.path.isdir(os.path.join(dir_path, e)) else ""
                html += f"<li><a href='{e}{slash}'>{e}{slash}</a></li>"
            html += "</ul></body></html>"
            self.send_html(200, html)

        def send_json(self, code, obj):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def send_html(self, code, text):
            data = text.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return ShnetHTTPHandler


def start_server_on_open_port(storage, start_port, node_name="rei", message_cb=None, static_dir=None, connect_cb=None, disconnect_cb=None):
    handler = make_shnet_handler(storage, node_name, message_cb, static_dir, connect_cb, disconnect_cb)
    for p in range(start_port, start_port + 50):
        try:
            # Try IPv6 dual-stack
            server = ThreadedHTTPServer(("::", p), handler, bind_v6=True)
            return server, p
        except Exception:
            try:
                # Fallback to IPv4
                server = ThreadedHTTPServer(("0.0.0.0", p), handler, bind_v6=False)
                return server, p
            except Exception:
                continue
    raise RuntimeError(f"Could not bind server between ports {start_port} and {start_port+50}")

# ---------------------------------------------------------------------------
# Chat Client
# ---------------------------------------------------------------------------

def send_chat_message(peer_url, message: ChatMessage, timeout=6.0):
    url = format_peer_url(peer_url) + "api/chat/message"
    data = message.to_json().encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "shnet-python/1.0.0"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
        return resp.status, body


def ping_peer(peer_url, timeout=3.0):
    try:
        url = format_peer_url(peer_url) + "api/chat/info"
        req = urllib.request.Request(url, headers={"User-Agent": "shnet-python/1.0.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                return json.loads(resp.read().decode("utf-8"))
    except Exception:
        pass
    return None


def connect_peer(peer_url, local_name, local_port, timeout=3.0):
    try:
        url = format_peer_url(peer_url) + "api/chat/connect"
        primary_url = get_primary_url(local_port)
        payload = json.dumps({"name": local_name, "port": local_port, "internet_url": primary_url}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "shnet-python/1.0.0"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if 200 <= resp.status < 300:
                data = json.loads(resp.read().decode("utf-8"))
                resolved_url = data.get("internet_url") or format_peer_url(peer_url)
                return True, data.get("name", "peer"), resolved_url
    except Exception:
        pass
    return False, None, None


def disconnect_peer(peer_url, local_name, timeout=1.5):
    try:
        url = format_peer_url(peer_url) + "api/chat/disconnect"
        payload = json.dumps({"name": local_name}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "shnet-python/1.0.0"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            pass
    except Exception:
        pass


def discover_host_lan(target_port):
    # 1. Localhost
    info = ping_peer(f"http://127.0.0.1:{target_port}")
    if info:
        return f"http://127.0.0.1:{target_port}/"

    # 2. LAN interfaces
    try:
        links = get_local_links(target_port)
        lan_prefixes = set()
        for l in links:
            url = l["url"]
            host = urllib.parse.urlparse(url).hostname
            if host and "." in host and not host.startswith("127."):
                lan_prefixes.add(host.rsplit(".", 1)[0] + ".")

        if lan_prefixes:
            import concurrent.futures
            with concurrent.futures.ThreadPoolExecutor(max_workers=40) as ex:
                futures = {}
                for prefix in lan_prefixes:
                    for i in range(1, 255):
                        test_ip = f"{prefix}{i}"
                        def probe(tip=test_ip):
                            try:
                                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                                    s.settimeout(0.15)
                                    if s.connect_ex((tip, target_port)) == 0:
                                        p_info = ping_peer(f"http://{tip}:{target_port}", timeout=0.5)
                                        if p_info:
                                            return f"http://{tip}:{target_port}/"
                            except Exception:
                                pass
                            return None
                        futures[ex.submit(probe)] = test_ip

                for f in concurrent.futures.as_completed(futures):
                    res = f.result()
                    if res:
                        return res
    except Exception:
        pass
    return None

# ---------------------------------------------------------------------------
# Interactive Chat Terminal (REPL)
# ---------------------------------------------------------------------------

def run_chat_repl(initial_peer=None, port=DEFAULT_PORT, name=None):
    name = resolve_nickname(name)
    storage = ChatStorage()
    active_peer = format_peer_url(initial_peer) if initial_peer else None
    peer_name = None
    is_host = True

    if not active_peer:
        is_host = True
    else:
        is_host = False

    listen_port = port

    def format_peer_display_name(sender):
        if not sender:
            return "peer"
        if sender.strip().lower() == name.strip().lower():
            return f"{sender.strip()}#peer"
        return sender.strip()

    def prompt():
        ptag = format_peer_display_name(peer_name) if peer_name else (normalize_peer(active_peer) if active_peer else "")
        if ptag:
            sys.stdout.write(f"{name}[{ptag}]> ")
        else:
            sys.stdout.write(f"{name}> ")
        sys.stdout.flush()

    def on_incoming(msg):
        t_str = datetime.fromtimestamp(msg.timestamp / 1000).strftime("%H:%M:%S")
        dname = format_peer_display_name(msg.sender)
        sys.stdout.write("\r\033[2K")
        if msg.text and msg.text.startswith("/me "):
            action = msg.text[4:].strip()
            sys.stdout.write(f"[ACTION] {t_str} * {dname} {action}\n")
        else:
            sys.stdout.write(f"[RECV] {t_str} <{dname}> {msg.text}\n")
        prompt()

    def on_connect(name_in, url_in):
        nonlocal active_peer, peer_name
        was_new = (active_peer != url_in)
        active_peer = url_in
        peer_name = name_in
        if was_new:
            dname = format_peer_display_name(name_in)
            sys.stdout.write(f"\r\033[2K[CONNECT] {url_in} <{dname}>\n")
            prompt()

    def on_disconnect(name_in):
        nonlocal active_peer, peer_name
        dname = format_peer_display_name(name_in)
        sys.stdout.write(f"\r\033[2K[DISCONNECT] <{dname}>\n")
        if is_host:
            active_peer = None
            peer_name = None
        prompt()

    server, actual_port = start_server_on_open_port(storage, listen_port, node_name=name,
                                                   message_cb=on_incoming,
                                                   connect_cb=on_connect,
                                                   disconnect_cb=on_disconnect)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    links = get_local_links(actual_port)
    primary_url = get_primary_url(actual_port)

    print(f"girchat {VERSION} [dual-stack ipv6]")
    print(f"node: {name} | port: {actual_port}")
    print(f"endpoint: {primary_url}")
    for l in links:
        badge = l.get("badge", "ipv6" if l["isIpv6"] else "ipv4")
        print(f"link [{badge}]: {l['url']}")
    if active_peer:
        dname = format_peer_display_name(peer_name) if peer_name else active_peer
        print(f"peer: {dname} @ {active_peer}")
    else:
        print("state: listening")
    print("commands: /nick /me /whois /peer /peers /history /qr /exit\n")

    def connect_to(target_url):
        nonlocal active_peer, peer_name
        ok, p_name, p_url = connect_peer(target_url, name, actual_port)
        if ok:
            peer_name = p_name
            active_peer = p_url
            dname = format_peer_display_name(peer_name)
            print(f"[CONNECT] {active_peer} <{dname}>")
        else:
            info = ping_peer(target_url)
            if info:
                peer_name = info.get("name", "node")
                active_peer = format_peer_url(target_url)
                dname = format_peer_display_name(peer_name)
                print(f"[ONLINE] {active_peer} <{dname}>")
            else:
                print(f"[OFFLINE] {target_url}")

    if active_peer:
        connect_to(active_peer)
        hist = storage.get_history(active_peer, 10)
        if hist:
            print(f"[HISTORY] {active_peer} ({len(hist)} messages)")
            for m in hist:
                t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                if m.direction == "OUT":
                    if m.text and m.text.startswith("/me "):
                        print(f"[ACTION] {t} * {name} {m.text[4:].strip()}")
                    else:
                        print(f"[SEND] {t} <{name}> {m.text}")
                else:
                    dname = format_peer_display_name(m.sender)
                    if m.text and m.text.startswith("/me "):
                        print(f"[ACTION] {t} * {dname} {m.text[4:].strip()}")
                    else:
                        print(f"[RECV] {t} <{dname}> {m.text}")

    prompt()

    try:
        while True:
            line = sys.stdin.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                prompt()
                continue

            if line.startswith("/"):
                parts = line.split(maxsplit=1)
                cmd = parts[0].lower()
                arg = parts[1].strip() if len(parts) > 1 else ""

                if cmd in ("/peer", "/connect"):
                    if not arg:
                        print(f"[PEER] {active_peer or 'none'}")
                    else:
                        connect_to(arg)
                        hist = storage.get_history(active_peer, 10)
                        if hist:
                            print(f"[HISTORY] {active_peer} ({len(hist)} messages)")
                            for m in hist:
                                t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                                if m.direction == "OUT":
                                    if m.text and m.text.startswith("/me "):
                                        print(f"[ACTION] {t} * {name} {m.text[4:].strip()}")
                                    else:
                                        print(f"[SEND] {t} <{name}> {m.text}")
                                else:
                                    dname = format_peer_display_name(m.sender)
                                    if m.text and m.text.startswith("/me "):
                                        print(f"[ACTION] {t} * {dname} {m.text[4:].strip()}")
                                    else:
                                        print(f"[RECV] {t} <{dname}> {m.text}")
                elif cmd in ("/nick", "/name"):
                    if arg:
                        old_name = name
                        name = arg
                        save_nickname(name)
                        if active_peer:
                            connect_peer(active_peer, name, actual_port)
                        print(f"[NICK] {old_name} -> {name}")
                    else:
                        print(f"[NICK] {name}")
                elif cmd == "/me":
                    if not arg:
                        print("usage: /me <action>")
                    elif not active_peer:
                        print("[ERROR] no active peer (use /peer <url>)")
                    else:
                        msg = ChatMessage(sender=name, peer=active_peer, text="/me " + arg, direction="OUT", reply_port=actual_port)
                        try:
                            code, body = send_chat_message(active_peer, msg)
                            if code == 200:
                                storage.save(msg)
                                t = datetime.now().strftime("%H:%M:%S")
                                print(f"[ACTION] {t} * {name} {arg}")
                            else:
                                print(f"[ERROR] send failed (HTTP {code}): {body}")
                        except Exception as ex:
                            print(f"[ERROR] send failed: delivery failed to {active_peer}: {ex}")
                elif cmd == "/whois":
                    print("[WHOIS]")
                    print(f"  local:    {name} (port {actual_port})")
                    print(f"  endpoint: {primary_url}")
                    if active_peer:
                        dname = format_peer_display_name(peer_name) if peer_name else "unknown"
                        print(f"  peer:     {dname} ({active_peer})")
                        if peer_name and peer_name.lower() == name.lower():
                            print(f"  alias:    {peer_name}#peer")
                        p_info = ping_peer(active_peer)
                        print(f"  state:    {'online' if p_info else 'offline'}")
                    else:
                        print("  peer:     none")
                elif cmd == "/history":
                    lim = int(arg) if arg.isdigit() else 20
                    h = storage.get_history(active_peer, lim)
                    print(f"[HISTORY] {active_peer or 'all'} ({len(h)} messages)")
                    for m in h:
                        t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                        if m.direction == "OUT":
                            if m.text and m.text.startswith("/me "):
                                print(f"[ACTION] {t} * {name} {m.text[4:].strip()}")
                            else:
                                print(f"[SEND] {t} <{name}> {m.text}")
                        else:
                            dname = format_peer_display_name(m.sender)
                            if m.text and m.text.startswith("/me "):
                                print(f"[ACTION] {t} * {dname} {m.text[4:].strip()}")
                            else:
                                print(f"[RECV] {t} <{dname}> {m.text}")
                    if not h:
                        print("  (none)")
                elif cmd == "/peers":
                    peers = storage.get_recent_peers()
                    print("[PEERS]")
                    if not peers:
                        print("  (none)")
                    else:
                        for p in peers:
                            mark = " (active)" if active_peer and p in active_peer else ""
                            print(f"  {p}{mark}")
                elif cmd == "/qr":
                    print(f"[QR] {primary_url}")
                    print(render_qr_ansi(primary_url))
                elif cmd in ("/info", "/status"):
                    print("[STATUS]")
                    print(f"  node:     {name}")
                    print(f"  role:     {'host' if is_host else 'client/host'}")
                    print(f"  port:     {actual_port}")
                    print(f"  endpoint: {primary_url}")
                    print(f"  peer:     {format_peer_display_name(peer_name) + ' @ ' + active_peer if (peer_name and active_peer) else (active_peer or 'none')}")
                    print("  links:")
                    for l in links:
                        badge = l.get("badge", "ipv6" if l["isIpv6"] else "ipv4")
                        print(f"    [{badge}] {l['url']}")
                elif cmd == "/clear":
                    sys.stdout.write("\033[H\033[2J")
                    sys.stdout.flush()
                elif cmd == "/help":
                    print("commands:")
                    print("  /nick <name>     set persistent nickname")
                    print("  /me <action>     send action message")
                    print("  /whois           show peer connection info")
                    print("  /peer <url>      connect to target node")
                    print("  /history [n]     show last [n] messages")
                    print("  /peers           list known peers")
                    print("  /info, /status   show node status & links")
                    print("  /qr              show terminal QR code")
                    print("  /clear           clear screen")
                    print("  /help            show command help")
                    print("  /exit, /quit     disconnect and exit")
                elif cmd in ("/exit", "/quit"):
                    break
                else:
                    print(f"[ERROR] unknown command: {cmd} (try /help)")
            else:
                if not active_peer:
                    print("[ERROR] no active peer (use /peer <url>)")
                else:
                    msg = ChatMessage(sender=name, peer=active_peer, text=line, direction="OUT", reply_port=actual_port)
                    try:
                        code, body = send_chat_message(active_peer, msg)
                        if code == 200:
                            storage.save(msg)
                            t = datetime.now().strftime("%H:%M:%S")
                            print(f"[SEND] {t} <{name}> {line}")
                        else:
                            print(f"[ERROR] send failed (HTTP {code}): {body}")
                    except Exception as ex:
                        print(f"[ERROR] send failed: delivery failed to {active_peer}: {ex}")

            prompt()
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        if active_peer:
            disconnect_peer(active_peer, name)
        server.shutdown()
        print("[STOP] node closed")

# ---------------------------------------------------------------------------
# Main CLI Entrypoint
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        prog="girchat",
        description="girchat / shnet - Dual-Stack IPv6 Peer Mesh Communicator (Python)"
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # chat
    p_chat = subparsers.add_parser("chat", help="Start interactive CLI-to-CLI peer messaging")
    p_chat.add_argument("peer", nargs="?", default=None, help="Target peer host:port")
    p_chat.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help=f"Local listening port (default: {DEFAULT_PORT})")
    p_chat.add_argument("-n", "--name", default=None, help="Your display identity nickname")

    # send
    p_send = subparsers.add_parser("send", help="Send a one-shot message to peer host")
    p_send.add_argument("peer", help="Target peer host:port")
    p_send.add_argument("message", help="Message body")
    p_send.add_argument("-n", "--name", default=None, help="Sender identity nickname")

    # history
    p_hist = subparsers.add_parser("history", help="View locally stored chat history")
    p_hist.add_argument("peer", nargs="?", default=None, help="Filter history by peer")
    p_hist.add_argument("-l", "--limit", type=int, default=50, help="Maximum messages to show")
    p_hist.add_argument("--clear", action="store_true", help="Clear history")

    # serve
    p_serve = subparsers.add_parser("serve", help="Serve static files from directory")
    p_serve.add_argument("dir", nargs="?", default=".", help="Directory to serve")
    p_serve.add_argument("-p", "--port", type=int, default=8080, help="Port to serve on")
    p_serve.add_argument("-q", "--qr", action="store_true", help="Print QR code")

    # links
    p_links = subparsers.add_parser("links", help="Discover active IPv6 & IPv4 URLs")
    p_links.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help="Port")

    # qr
    p_qr = subparsers.add_parser("qr", help="Print terminal ANSI QR code")
    p_qr.add_argument("text", nargs="?", default=None, help="Text/URL to encode")

    # version
    subparsers.add_parser("version", help="Show version information")

    # If first argument looks like an IP or host, treat as chat target
    if len(sys.argv) > 1 and sys.argv[1] not in ("chat", "send", "history", "serve", "links", "qr", "version", "--help", "-h"):
        sys.argv.insert(1, "chat")

    args = parser.parse_args()

    if not args.command or args.command == "help":
        parser.print_help()
        sys.exit(0)

    if args.command == "version":
        print(f"girchat / shnet version {VERSION} (Python {sys.version.split()[0]})")
        print("Dual-Stack IPv6 Peer Mesh Communicator.")
        sys.exit(0)

    if args.command == "chat":
        run_chat_repl(initial_peer=args.peer, port=args.port, name=args.name)

    elif args.command == "send":
        peer_url = format_peer_url(args.peer)
        sender_name = resolve_nickname(args.name)
        msg = ChatMessage(sender=sender_name, peer=peer_url, text=args.message, direction="OUT")
        try:
            code, body = send_chat_message(peer_url, msg)
            if code == 200:
                storage = ChatStorage()
                storage.save(msg)
                print(f"[OK] sent {peer_url} (id: {msg.id})")
            else:
                print(f"[ERROR] failed (HTTP {code}): {body}", file=sys.stderr)
                sys.exit(1)
        except Exception as e:
            print(f"[ERROR] delivery failed {peer_url}: {e}", file=sys.stderr)
            sys.exit(1)

    elif args.command == "history":
        storage = ChatStorage()
        if args.clear:
            storage.clear(args.peer)
            print(f"Cleared chat history{' for ' + args.peer if args.peer else ''}.")
            sys.exit(0)
        history = storage.get_history(args.peer, args.limit)
        if not history:
            print(f"No chat messages recorded{' for ' + args.peer if args.peer else ''}.")
            sys.exit(0)
        print(f"Stored Chat History ({len(history)} messages):")
        for m in history:
            t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%Y-%m-%d %H:%M:%S")
            d = f"OUT -> {m.peer}" if m.direction == "OUT" else f"IN  <- {m.sender}"
            print(f"[{t}] [{d:<22}] {m.text}")

    elif args.command == "links":
        links = get_local_links(args.port)
        print(f"Discovered links on port {args.port}:")
        for l in links:
            tag = "[IPv6]" if l["isIpv6"] else "[IPv4]"
            print(f"  {tag:<7} {l['label']+':':<20} {l['url']}")

    elif args.command == "qr":
        text = args.text or get_primary_url(DEFAULT_PORT)
        print(f"QR Code for: {text}")
        print(render_qr_ansi(text))

    elif args.command == "serve":
        target_dir = os.path.abspath(args.dir)
        storage = ChatStorage()
        server, actual_port = start_server_on_open_port(storage, args.port, static_dir=target_dir)
        primary = get_primary_url(actual_port)
        print(f"Serving {target_dir} on {primary}")
        if args.qr:
            print(render_qr_ansi(primary))
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            server.shutdown()
            print("\nStopped.")


if __name__ == "__main__":
    main()
