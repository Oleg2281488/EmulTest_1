package nes.emulator.cartridge;

public class NROM implements GenericCartridge {
    NROM(int a, int b)
    {

    }

    public Byte getMemAt(Short addr)
    {
        return 1;
    }

    public Boolean setMemAt(Short addr, Byte val)
    {

        return true;
    }
}
