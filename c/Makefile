CC = musl-gcc
CFLAGS = -O2 -Wall -Wextra -static
LDFLAGS = -pthread

all: shnet

shnet: shnet.c qrcodegen.c
	$(CC) $(CFLAGS) shnet.c qrcodegen.c -o shnet $(LDFLAGS)
	strip shnet 2>/dev/null || true

clean:
	rm -f shnet shnet-musl *.o
