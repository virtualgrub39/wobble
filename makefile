OUT = wobble
SRC = main.c

CFLAGS += -Wall -Wextra
CFLAGS += -std=c99
CFLAGS += -ggdb

LDFLAGS += -lm

all: $(OUT)

OBJ = $(SRC:.c=.o)

%.o: %.c
	$(CC) -c -o $@ $(CFLAGS) $<

$(OUT): $(OBJ)
	$(CC) -o $@ $(CFLAGS) $^ $(LDFLAGS)
