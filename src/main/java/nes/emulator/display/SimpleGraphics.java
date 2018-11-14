package nes.emulator.display;

import nes.emulator.console.PPU;
import nes.emulator.console.Palette;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;


public enum SimpleGraphics {
    INSTANCE;

    JFrame frame;
    JPanel panel;
    public Palette palette0;
    Integer scale = 2;
    int width = 256 * scale;
    int height = 240 * scale;


    public Color[] buffer = new Color[256 * 240];


    SimpleGraphics() {

        for (int j = 0; j < 256 * 240; j++) {
            buffer[j] = Color.BLACK;
        }


        frame = new JFrame("Emul");

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(width, height);
        frame.setTitle("Emul");
        frame.setResizable(false);
        //setUndecorated(true);

        panel = new GPanel();
        frame.getContentPane().add(panel);
        frame.pack();
        frame.setResizable(false);
        frame.setVisible(true);


    }


    public void addPixel(Integer curPixel, Integer curLine, Integer color, Palette curPalette) {
        buffer[(256 * curLine) + curPixel] = curPalette.getColor(color);

    }





    class GPanel extends JPanel implements ActionListener {
        Timer timer = new Timer(16, this);
        int x = 5, y = 5;
        int h = 20, w = 20;
        int xi = 10, yi = 10;




        public GPanel() {
            super();

            setDoubleBuffered(true);

            setBackground(Color.black);
            setPreferredSize(new Dimension(width, height));


            timer.start();
        }

        public void paint(Graphics g) {
            super.paint(g);


            PPU.INSTANCE.drawScreen();

            for (int i = 0; i < 256 * 240; i++) {
                g.setColor(buffer[i]);


                int x = i % 256;
                int y = i / 256;
                x*=scale; y*=scale;
                g.fillRect(x, y, scale, scale);
                //g.drawLine(x, y, x+scale-1, y+scale-1);
                //g.setColor(Color.BLACK);
            }

            //g.drawRect(x, y, w, h);



        }

        @Override
        public void actionPerformed(ActionEvent e) {



            repaint();

            for (int j = 0; j < 256 * 240; j++) {
                buffer[j] = Color.BLACK;
            }


        }


    }

    public void init() {

    }


}