package dev.superjavendo.bus;

/** 24-bit address space seen by the CPU. */
public interface Memory {
    int read(int addr);
    void write(int addr, int value);
}
