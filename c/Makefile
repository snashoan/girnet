CC = musl-gcc
CFLAGS = -O2 -Wall -Wextra -static
LDFLAGS = -pthread

all: shnet

shnet: shnet.c qrcodegen.c
	$(CC) $(CFLAGS) shnet.c qrcodegen.c -o shnet $(LDFLAGS)
	strip shnet 2>/dev/null || true

deploy: shnet
	mkdir -p /root/workspace/download
	rm -f /root/workspace/download/girchat /root/workspace/download/shnet
	cp shnet /root/workspace/download/girchat
	cp shnet /root/workspace/download/shnet
	mkdir -p ../bin
	rm -f ../bin/girchat ../bin/shnet
	cp shnet ../bin/girchat
	cp shnet ../bin/shnet

clean:
	rm -f shnet shnet-musl *.o
