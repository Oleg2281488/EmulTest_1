package nes.emulator.console;


//http://www.emuverse.ru/wiki/MOS_Technology_6502/Система_команд
//http://nparker.llx.com/a2/opcodes.html
//https://www.atariarchives.org/alp/appendix_1.php

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public enum Proc {
    INSTANCE;

    private byte regA;
    private byte regX;
    private byte regY;
    private short regPC;
    private byte regS;

    private Logger log;

    // регистр флагов
    private byte C; //:1; // carry
    private byte Z; //:1; // zero
    private byte I; //:1; // interrupt 0==enabled
    private byte D; //:1; // decimal mode
    //private byte B; //:1; // currently in break(BRK) interrupt
    //private byte NU; //:1; // always 1
    private byte V; //:1; // oVerflow
    private byte N; //:1; // negative(?)

    private long RESETtick;
    private long CPUticks;

    private int pageCrossed = 0;

    private void setZ(byte z) {
        Z = (byte) (z == 0 ? 1 : 0);
    }

    private void setN(byte n) {
        N = (byte) ((n & 0x80) == 0 ? 0 : 1);
    }

    private void setC(int c) {
        C = (byte) (c >> 8 & 1);
    }

    private void setBorrow(int c) {
        C = (byte) (c >> 8 & 1 ^ 1);
    }


    private Memory m;

    public long getEmulTickCount()
    {
        return RESETtick+CPUticks*12;
    }

    public long getCPUTickCount()
    {
        return CPUticks;
    }

    public long getPPUTickCount()
    {
        return RESETtick+CPUticks*3;
    }

    public void InitiateReset()
    {
        RESETtick = getEmulTickCount();
        CPUticks = 0;
        regS-=3;
        I=1;
        regPC=m.getMemAtW((short) 0xFFFC);
        //m.setMemAt(0x4015, 0); // APU
    }

    public void init() {
        m = Memory.INSTANCE;
        regPC = m.getMemAtW((short) 0xFFFC);
        //m=mem;

        try
        {
            LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(("handlers = java.util.logging.FileHandler\n" +
                    "java.util.logging.FileHandler.level =ALL\n" +
                    "java.util.logging.FileHandler.formatter =java.util.logging.SimpleFormatter\n" +
                    "java.util.logging.SimpleFormatter.format=%4$s: %5$s%n\n" +
                    "java.util.logging.FileHandler.limit = 100000000\n" +
                    "java.util.logging.FileHandler.pattern   = полено.txt").getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e)
        {
            log = null;
            return;
        }
        log = Logger.getLogger("proc.java");
        log.setLevel(Level.ALL);
    }

    Proc() {
        regA = regX = regY = 0;
        regS = (byte) 0xFF;

    }


    // Вход: При входе в эту функцию regPC указывает на первый байт операнда
    // addrmode - три бита, выдранные из инструкции
    // Выход: opAddr - адрес операнда
    // Результат: содержимое операнда
    short takeoperaddr(int addrmode) {
	/*
	    Для ZZ=01 и ZZ=11(недокументированные инструкции)

       000   ind,x
       001   zp
       010   immed
       011   abs
       100   ind,y
       101   zp,x
       110   abs,y
       111   abs,x
	*/
	/*
	    Для ZZ=10

		000   immed
		010   impl
		100   ИНВАЛИД/КРЕШ
		110   impl
	*/
	/*
	    Для ZZ=00

		000   immed
		010   impl
		100   rel(инструкции перехода)
		110   impl
	*/

        short opAddr = 0;
        short opAddr_i = 0;
        pageCrossed = 0;
        switch (addrmode) {
            case 0: // =(ind, x)
                opAddr = (short) (255 & (regX + m.getMemAt(regPC++)));
                opAddr = (short) m.getMemAtWarpedW(opAddr);
                CPUticks+=4;
                break;
            case 1: //zeropage
                opAddr = m.getMemAt(regPC++);
                CPUticks++;
                break;
            case 2: //immed
                opAddr = regPC++;
                break;
            case 3: //abs
                opAddr = m.getMemAtW(regPC);
                regPC += 2;
                CPUticks+=2;
                break;
            case 4: //(ind), y
                opAddr_i = m.getMemAtWarpedW((short)(m.getMemAt(regPC++)&0xFF));
                opAddr = (short) (opAddr_i + (0xFF&regY));
                if((opAddr&0xFF00)!=(opAddr_i&0xFF00)) CPUticks++; else pageCrossed=1;
                CPUticks+=3;
                break;
            case 5: //zp, x
                byte pc_i = m.getMemAt((short) (regPC - 1)), pc_0 = m.getMemAt(regPC);
                opAddr = m.getMemAt((short) (255 & (pc_0 + (pc_i == 0x96 || pc_i == 0xB6 ? regY : regX))));
                regPC++;
                CPUticks+=2;
                break;
            case 6: //abs, y
                opAddr_i = m.getMemAtW(regPC);
                opAddr = (short) ((0xFF&regY) + opAddr_i );
                if((opAddr&0xFF00)!=(opAddr_i&0xFF00)) CPUticks++; else pageCrossed=1;
                regPC += 2;
                CPUticks+=2;
                break;
            case 7: //abs, x
                opAddr_i = m.getMemAtW(regPC);
                pc_i = m.getMemAt((short) (regPC - 1));
                opAddr = (short) (((pc_i == 0xBE ? regY : regX)&0xFF) + opAddr_i );
                if((opAddr&0xFF00)!=(opAddr_i&0xFF00)) CPUticks++; else pageCrossed=1;
                regPC += 2;
                CPUticks+=2;
                break;
        }
        return opAddr;
    }

    short takereladdr() {
        short branchoffset = m.getMemAt(regPC++);
        int branchaddress = regPC + branchoffset;
        return (short) branchaddress;
    }

    void CMP(byte a, byte b) {
        int cmp = ((a & 0xff) - (b & 0xff));
        byte cmpb = (byte) cmp;
        setBorrow(cmp);
        setZ(cmpb);
        setN(cmpb);
    }

    int DMARemaining = 0;
    short DMAHigh = (short)0xFF00;
    final static short PPU_OAM_DATA_ADDRESS = (short)0x2004;
    final static short OAM_DMA_ADDRESS = (short)0x4014;

    public void SetupDMA(byte dmahigh)
    {
        DMARemaining = 0x100;
        DMAHigh = (short)(dmahigh<<8);
        CPUticks++; // +1 если инструкция заканчивается на нечетном такте цпу
    }

    void DMA()
    {
        m.setMemAt(PPU_OAM_DATA_ADDRESS, m.getMemAt(DMAHigh++));
        DMARemaining--;
        CPUticks+=2;
    }

    void ComplainAboutDMA()
    {
        log.log(Level.SEVERE, "RRW instruction tried to write 0x4014 twice. Only the second write will proceed");
    }

    boolean getDMARunning()
    {
        return DMARemaining!=0;
    }

    public void Step() {
        // LDA FF, TAX, INX, STA 1,X, LDY #0
        //char* opcodes = "\xA9\xFE\xAA\xE8\x95\x01\xA4\x00";

        if(getDMARunning()) { DMA(); return; }

        byte command = m.getMemAt(regPC++);
        log.log(Level.INFO, String.format("command: %02x", command));

        byte oper = 0;
        Short opaddr = 0;

        // XXXYYYZZ
        // XXX: код команды
        // YYY: режим адресации
        // ZZ: класс команды

        int addrmode = (command >> 2) & 7;
        int comcode = (command >> 5) & 7;
        int comclass = command & 3;

        CPUticks +=2;

        // interpret command's code to ascertain addressing mode
        switch (comclass) {
            case 2:
                if ((addrmode & 1)==0 && comcode!=5) break;
                // 2, 5, 0:immed

                log.log(Level.INFO, String.format("operand: %02x(+second byte %02x) ", m.getMemAt(regPC), m.getMemAt((short) (regPC + 1))));
                opaddr = takeoperaddr(addrmode==0?2:addrmode);
                oper = m.getMemAt(opaddr);
                log.log(Level.INFO, String.format("decoded: (%04x)%02x", opaddr, oper));
                break;
                //commands either work as expected or halt/nop
            case 0:
                // сложнааааааа
                if (comcode == 0 || ((addrmode & 1) == 0 && (comcode < 5 || addrmode != 0))) break;
                //if (comcode != 0 && ((addrmode & 1) || (comcode >= 5 && !addrmode))) {
                log.log(Level.INFO, String.format("operand: %02x(+second byte %02x) ", m.getMemAt(regPC), m.getMemAt((short) (regPC + 1))));
                opaddr = takeoperaddr(addrmode==0?2:addrmode);
                oper = m.getMemAt(opaddr);
                log.log(Level.INFO, String.format("decoded: (%04x)%02x", opaddr, oper));
                break;
            case 3:
                //undocumented
            case 1:
                log.log(Level.INFO, String.format("operand: %02x(+second byte %02x) ", m.getMemAt(regPC), m.getMemAt((short) (regPC + 1))));
                opaddr = takeoperaddr(addrmode);
                oper = m.getMemAt(opaddr);
                log.log(Level.INFO, String.format("decoded: (%04x)%02x", opaddr, oper));
                break;
        }
        log.log(Level.INFO, "executing ");
        // exec command
        switch (comclass) {
            case 0:
                if (addrmode == 4) {
                    short newaddr = takereladdr();
                    // рип олег 09.11 земля пухом братишка
                    int branches[] = {N, V, C, Z};
                    int branchtaken = comcode & 1 ^ 1;
                    branchtaken ^= branches[comcode >> 1 & 3];
                    /*int branchtaken = 0;
                    switch(comcode)
                    {
                        case 0: //BPL
                        case 1: //BMI
                            branchtaken = N;
                            break;
                        case 2: //BVC
                        case 3: //BVS
                            branchtaken = V;
                            break;
                        case 4: //BCC
                        case 5: //BCS
                            branchtaken = C;
                            break;
                        case 6: //BNE
                        case 7: //BEQ
                            branchtaken = Z;
                            break;
                    }
                    if((comcode&1)==0) branchtaken = 1-branchtaken;*/
                    if (branchtaken == 1)
                    {
                        CPUticks++;
                        // If branch occurs to different page, an additional instruction fetch is made
                        if(((newaddr^regPC)&0xFF00)!=0) CPUticks++;
                        regPC = newaddr;
                    }
                    log.log(Level.INFO, String.format("BRANCH. Cond(%02x), TGT(%02x)", branches[comcode >> 1 & 3], newaddr));
                } else
                    switch (comcode) {
                        case 0:
                            switch (addrmode) {
                                case 0:
                                    short stackaddr = (short) (0x100 + (regS & 0xFF));
                                    int P = C | (Z << 1) | (I << 2) | (D << 3) | (1 << 4) | (1 << 5) | (V << 6) | (N << 7);
                                    regPC++;
                                    m.setMemAt(stackaddr--, (byte) (regPC >> 8));
                                    m.setMemAt(stackaddr--, (byte) regPC);
                                    m.setMemAt(stackaddr, (byte) (P));
                                    regS -= 3;
                                    I = 1;
                                    log.log(Level.INFO, String.format("BRK. RET(%02x)", regPC));
                                    regPC = m.getMemAtW((short) 0xFFFE);
                                    CPUticks+=5;
                                    break;
                                case 2:
                                    stackaddr = (short) (0x100 + (regS & 0xFF));
                                    P = C | (Z << 1) | (I << 2) | (D << 3) | (1<<4) | (1 << 5) | (V << 6) | (N << 7);
                                    log.log(Level.INFO, String.format("PHP. P(%02x) => [S](%02x)", P, m.getMemAt(stackaddr)));
                                    m.setMemAt(stackaddr, (byte) (P));
                                    regS--;
                                    CPUticks++;
                                    break;
                                case 6:
                                    log.log(Level.INFO, String.format("CLC. C(%02x)", C));
                                    C = 0;
                                    break;
                            }
                            break;
                        case 1:
                            switch (addrmode) {
                                case 0:
                                    short stackaddr = (short) (0x100 + (regS & 0xFF));
                                    short jsraddr = m.getMemAtW(regPC++);
                                    m.setMemAt(stackaddr--, (byte) (regPC >> 8));
                                    m.setMemAt(stackaddr, (byte) regPC);
                                    regPC = jsraddr;
                                    regS -= 2;
                                    log.log(Level.INFO, String.format("JSR. TGT(%02x)", regPC));
                                    CPUticks+=4;
                                    break;
                                case 1:
                                case 3:
                                    log.log(Level.INFO, String.format("BIT. M(%02x) <&> A(%02x)", oper, regA));
                                    int bitimterm = regA & oper & 0xFF;
                                    byte bitimtermb = (byte) bitimterm;
                                    setZ(bitimtermb);
                                    setN(bitimtermb);
                                    V = (byte) ((bitimterm & 0x40) == 0 ? 0 : 1);
                                    break;
                                case 2:
                                    regS++;
                                    stackaddr = (short) (0x100 + (regS & 0xFF));
                                    int P = C | (Z << 1) | (I << 2) | (D << 3) | (1 << 5) | (V << 6) | (N << 7);
                                    byte NP = m.getMemAt(stackaddr);
                                    log.log(Level.INFO, String.format("PLP. [S](%02x) => P(%02x)", NP, P));
                                    C = (byte) (NP & 1);
                                    Z = (byte) (NP >> 1 & 1);
                                    I = (byte) (NP >> 2 & 1);
                                    D = (byte) (NP >> 3 & 1);
                                    V = (byte) (NP >> 6 & 1);
                                    N = (byte) (NP >> 7 & 1);
                                    CPUticks+=2;
                                    break;
                                case 6:
                                    log.log(Level.INFO, String.format("SEC. C(%02x)", C));
                                    C = 1;
                                    break;
                            }
                            break;
                        case 2:
                            switch (addrmode) {
                                case 0:

                                    short stackaddr = (short) (0x100 + (regS & 0xFF));
                                    byte NP = m.getMemAt(++stackaddr);
                                    C = (byte) (NP & 1);
                                    Z = (byte) (NP >> 1 & 1);
                                    I = (byte) (NP >> 2 & 1);
                                    D = (byte) (NP >> 3 & 1);
                                    V = (byte) (NP >> 6 & 1);
                                    N = (byte) (NP >> 7 & 1);
                                    stackaddr++;
                                    regPC = m.getMemAtW(stackaddr);
                                    regS += 3;
                                    CPUticks+=4;
                                    log.log(Level.INFO, String.format("RTI. TGT(%02x)", regPC));
                                    break;
                                case 2:
                                    stackaddr = (short) (0x100 + (regS & 0xFF));
                                    log.log(Level.INFO, String.format("PHA. A(%02x) => [S](%02x)", regA, m.getMemAt(stackaddr)));
                                    m.setMemAt(stackaddr, regA);
                                    regS--;
                                    CPUticks++;
                                    break;
                                case 3:
                                    short jumptgt = m.getMemAtW(regPC);
                                    CPUticks++;
                                    log.log(Level.INFO, String.format("JMP tgt: %04x", jumptgt));
                                    regPC = jumptgt;
                                    break;
                                case 6:
                                    log.log(Level.INFO, String.format("CLI. I(%02x)", I));
                                    I = 0;
                                    break;
                            }
                            break;
                        case 3:
                            switch (addrmode) {
                                case 0:
                                    regS++;
                                    short stackaddr = (short) (0x100 + (regS & 0xFF));
                                    regPC = m.getMemAtW(stackaddr);
                                    regPC++;
                                    regS++;
                                    CPUticks+=4;
                                    log.log(Level.INFO, String.format("RTS. TGT(%02x)", regPC));
                                    break;
                                case 2:
                                    regS++;
                                    stackaddr = (short) (0x100 + (regS & 0xFF));
                                    byte NA = m.getMemAt(stackaddr);
                                    log.log(Level.INFO, String.format("PLA. [S](%02x) => A(%02x)", NA, regA));
                                    regA = NA;
                                    setZ(regA);
                                    setN(regA);
                                    CPUticks+=2;
                                    break;
                                case 3:
                                    short jumptgta = m.getMemAtW(regPC);
                                    short jumptgt = m.getMemAtWarpedW(jumptgta);
                                    CPUticks+=3;
                                    log.log(Level.INFO, String.format("JMP tgt: %04x", jumptgt));
                                    regPC = jumptgt;
                                    break;
                                case 6:
                                    log.log(Level.INFO, String.format("SEI. I(%02x)", I));
                                    I = 1;
                                    break;
                            }
                            break;
                        case 4:
                            switch (addrmode) {
                                case 1:
                                case 3:
                                case 5:
                                    log.log(Level.INFO, String.format("STY. Y(%02x) => M(%02x)", regY, oper));
                                    m.setMemAt(opaddr, regY);
                                    break;
                                case 2:
                                    regY--;
                                    log.log(Level.INFO, String.format("DEY. result: %02x", regY));
                                    setZ(regY);
                                    setN(regY);
                                    break;
                                case 6:
                                    log.log(Level.INFO, String.format("TYA. Y(%02x) => A(%02x)", regY, regA));
                                    regA = regY;
                                    setZ(regA);
                                    setN(regA);
                                    break;
                            }
                            break;
                        case 5:
                            switch (addrmode) {
                                case 0:
                                case 1:
                                case 3:
                                case 5:
                                case 7:
                                    log.log(Level.INFO, String.format("LDY. M(%02x) => Y(%02x)", oper, regY));
                                    regY = oper;
                                    setZ(regY);
                                    setN(regY);
                                    break;
                                case 2:
                                    log.log(Level.INFO, String.format("TAY. A(%02x) => Y(%02x)", regA, regY));
                                    regY = regA;
                                    setZ(regY);
                                    setN(regY);
                                    break;
                                case 6:
                                    log.log(Level.INFO, String.format("CLV. V(%02x)", V));
                                    V = 0;
                                    break;
                            }
                            break;
                        case 6:
                            switch (addrmode) {
                                case 0:
                                case 1:
                                case 3:
                                    log.log(Level.INFO, String.format("CPY. M(%02x) <> Y(%02x)", oper, regY));
                                    CMP(regY, oper);
                                    break;
                                case 2:
                                    regY++;
                                    log.log(Level.INFO, String.format("INY. result: %02x", regY));
                                    setZ(regY);
                                    setN(regY);
                                    break;
                            }
                            break;
                        case 7:
                            switch (addrmode) {
                                case 0:
                                case 1:
                                case 3:
                                    log.log(Level.INFO, String.format("CPX. M(%02x) <> X(%02x)", oper, regX));
                                    CMP(regX, oper);
                                    break;
                                case 2:
                                    regX++;
                                    log.log(Level.INFO, String.format("INX. result: %02x", regX));
                                    setZ(regX);
                                    setN(regX);
                                    break;
                            }
                    }
                break;
            case 1:
                switch (comcode) {
                    case 0:
                        log.log(Level.INFO, String.format("ORA. M(%02x) |> A(%02x)", oper, regA));
                        regA |= oper;
                        setZ(regA);
                        setN(regA);
                        break;
                    case 1:
                        log.log(Level.INFO, String.format("AND. M(%02x) &> A(%02x)", oper, regA));
                        regA &= oper;
                        setZ(regA);
                        setN(regA);
                        break;
                    case 2:
                        log.log(Level.INFO, String.format("EOR. M(%02x) ^> A(%02x)", oper, regA));
                        regA ^= oper;
                        setZ(regA);
                        setN(regA);
                        break;
                    case 3:
                        log.log(Level.INFO, String.format("ADC. M(%02x) +> A(%02x)", oper, regA));
                        // bytes get sign-extended into ints. mask the lowest byte to get carry
                        int intermediate = ((regA & 0xff) + (oper & 0xff) + C);
                        setC(intermediate);
                        regA = (byte) intermediate;
                        //Overflow occurs if (M^result)&(N^result)&0x80 is nonzero. That is, if the sign of both inputs is different from the sign of the result.
                        V = (byte) (((regA ^ intermediate) & (oper ^ intermediate) & 80) == 0 ? 0 : 1);
                        setZ(regA);
                        setN(regA);
                        break;
                    case 4:
                        log.log(Level.INFO, String.format("STA. A(%02x) => M(%02x)", regA, oper));
                        m.setMemAt(opaddr, regA);
                        CPUticks+=pageCrossed;
                        break;
                    case 5:
                        regA = (byte) oper;
                        log.log(Level.INFO, String.format("LDA. operand: %02x", regA));
                        setZ(oper);
                        setN(oper);
                        break;
                    case 6:
                        log.log(Level.INFO, String.format("CMP. M(%02x) <> A(%02x)", oper, regA));
                        CMP(regA, oper);
                        break;
                    case 7:
                        log.log(Level.INFO, String.format("SBC. M(%02x) -> A(%02x)", oper, regA));
                        // http://www.righto.com/2012/12/the-6502-overflow-flag-explained.html
                        // bytes get sign-extended into ints. mask the lowest byte to get borrow.
                        // borrow is complement of carry. 1^1=0; 1^0=1
                        // M - N - B
                        // = M + (ones complement of N) + C
                        int sbcinterm = ((regA & 0xff) + (~oper & 0xff) + C);
                        // get borrow value
                        setBorrow(sbcinterm);
                        //Overflow occurs if (M^result)&(N^result)&0x80 is nonzero. That is, if the sign of both inputs is different from the sign of the result.
                        V = (byte) (((regA ^ sbcinterm) & (~oper ^ sbcinterm) & 0x80) == 0 ? 0 : 1);
                        regA = (byte) sbcinterm;
                        setZ(regA);
                        setN(regA);
                        break;

                }
                break;
            case 2:
                switch (comcode) {
                    case 0:
                        switch (addrmode) {
                            case 1:
                            case 3:
                            case 5:
                            case 7:
                                log.log(Level.INFO, String.format("ASL. M: %02x", oper));
                                m.setMemAt(opaddr, oper);
                                int intermediate = oper << 1;
                                setC(intermediate);
                                m.setMemAt(opaddr, (byte)intermediate);
                                setZ(oper);
                                setN(oper);
                                if(opaddr==OAM_DMA_ADDRESS) ComplainAboutDMA();
                                CPUticks+=2+pageCrossed;
                                break;
                            case 2:
                                log.log(Level.INFO, String.format("ASL. A: %02x", regA));
                                intermediate = regA << 1;
                                setC(intermediate);
                                regA = (byte)intermediate;
                                setZ(regA);
                                setN(regA);
                                break;
                        }
                        break;
                    case 1:
                        switch (addrmode) {
                            case 1:
                            case 3:
                            case 5:
                            case 7:
                                log.log(Level.INFO, String.format("ROL. M: %02x", oper));
                                // to roll left: shift left normally
                                // shift right 7 then mask the lowest bit (java sign-extends bytes into ints. for negative numbers this means all leftmost bits become 1's)
                                // operator precedence: <</>> > & > |
                                int intermediate = oper << 1 | C;
                                setC(intermediate);
                                oper = (byte) (intermediate);
                                m.setMemAt(opaddr, oper);
                                setZ(oper);
                                setN(oper);
                                if(opaddr==OAM_DMA_ADDRESS) ComplainAboutDMA();
                                CPUticks+=2+pageCrossed;
                                break;
                            case 2:
                                log.log(Level.INFO, String.format("ROL. A: %02x", regA));
                                intermediate = regA << 1 | C;
                                setC(intermediate);
                                regA = (byte) (intermediate);
                                setZ(regA);
                                setN(regA);
                                break;
                        }
                        break;
                    case 2:
                        switch (addrmode) {
                            case 1:
                            case 3:
                            case 5:
                            case 7:
                                log.log(Level.INFO, String.format("LSR. M: %02x", oper));
                                m.setMemAt(opaddr, oper);
                                C = (byte)(oper&1);
                                oper = (byte) ((oper & 0xff) >> 1);
                                m.setMemAt(opaddr, oper);
                                setZ(oper);
                                setN(oper);
                                if(opaddr==OAM_DMA_ADDRESS) ComplainAboutDMA();
                                CPUticks+=2+pageCrossed;
                                break;
                            case 2:
                                log.log(Level.INFO, String.format("LSR. A: %02x", regA));
                                C = (byte)(regA&1);
                                regA = (byte) ((regA & 0xff) >> 1);
                                setZ(regA);
                                setN(regA);
                                break;
                        }
                        break;
                    case 3:
                        switch (addrmode) {
                            case 1:
                            case 3:
                            case 5:
                            case 7:
                                log.log(Level.INFO, String.format("ROR. M: %02x", oper));
                                m.setMemAt(opaddr, oper);
                                // to roll right: shift right  then mask the lowest 7 bits due to sign-extension
                                // shift left 7 normally
                                // operator precedence: <</>> > & > |
                                int intermediate = oper >> 1 & 0x7F | C << 7;
                                C = (byte)(oper&1);
                                oper = (byte) (intermediate);
                                m.setMemAt(opaddr, oper);
                                setZ(oper);
                                setN(oper);
                                if(opaddr==OAM_DMA_ADDRESS) ComplainAboutDMA();
                                CPUticks+=2+pageCrossed;
                                break;
                            case 2:
                                log.log(Level.INFO, String.format("ROR. A: %02x", regA));
                                intermediate = regA >> 1 & 0x7F | C << 7;
                                C = (byte)(regA&1);
                                regA = (byte) (intermediate);
                                setZ(regA);
                                setN(regA);
                                break;
                        }
                        break;
                    case 4:
                        switch (addrmode) {
                            case 1:
                            case 3:
                            case 5:
                                log.log(Level.INFO, String.format("STX. X(%02x) => M(%02x)", regX, oper));
                                m.setMemAt(opaddr, regX);
                                break;
                            case 2:
                                log.log(Level.INFO, String.format("TXA. X(%02x) => A(%02x)", regX, regA));
                                regA = regX;
                                setZ(regA);
                                setN(regA);
                                break;
                            case 6:
                                log.log(Level.INFO, String.format("TXS. X(%02x) => S(%02x)", regX, regS));
                                regS = regX;
                                break;
                        }
                        break;
                    case 5:
                        switch (addrmode) {
                            case 0:
                            case 1:
                            case 3:
                            case 5:
                            case 7:
                                log.log(Level.INFO, String.format("LDX. M(%02x) => X(%02x)", oper, regX));
                                regX = oper;
                                setZ(regX);
                                setN(regX);
                                break;
                            case 2:
                                log.log(Level.INFO, String.format("TAX. A(%02x) => X(%02x)", regA, regX));
                                regX = regA;
                                setZ(regX);
                                setN(regX);
                                break;
                            case 6:
                                log.log(Level.INFO, String.format("TSX. S(%02x) => X(%02x)", regS, regX));
                                regX = regS;
                                setZ(regX);
                                setN(regX);
                                break;
                        }
                        break;
                    case 6:
                        if ((addrmode & 1) != 0) {
                            m.setMemAt(opaddr, oper);
                            m.setMemAt(opaddr, (byte) (oper - 1));
                            oper = m.getMemAt(opaddr);
                            log.log(Level.INFO, String.format("DEC. result: %02x", m.getMemAt(opaddr)));
                            setZ(oper);
                            setN(oper);
                            if(opaddr==OAM_DMA_ADDRESS) ComplainAboutDMA();
                            CPUticks+=2+pageCrossed;
                        } else if (addrmode == 2) {
                            regX--;
                            log.log(Level.INFO, String.format("DEX. result: %02x", regY));
                            setZ(regX);
                            setN(regX);
                        } else log.log(Level.INFO, "NOP");
                        break;
                    case 7:
                        if ((addrmode & 1) != 0) {
                            m.setMemAt(opaddr, oper);
                            m.setMemAt(opaddr, (byte) (oper + 1));
                            oper = m.getMemAt(opaddr);
                            log.log(Level.INFO, String.format("INC. result: %02x", oper));
                            setZ(oper);
                            setN(oper);
                            if(opaddr==OAM_DMA_ADDRESS) ComplainAboutDMA();
                            CPUticks+=2+pageCrossed;
                        } else
                            log.log(Level.INFO, "NOP");
                        break;
                }
        }
        log.log(Level.INFO, String.format("terminated. A: %02x, X: %02x, Y: %02x, PC: %04x, S: %02x\n", regA, regX, regY, regPC, regS));
        //log.log(Level.INFO, "\n");
    }
}
