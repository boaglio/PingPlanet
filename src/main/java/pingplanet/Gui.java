package pingplanet;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/** The PingPlanet window: Internet, LAN and Server tabs under a header. */
final class Gui {

    /** Only touched once the window opens: terminal runs never create a preferences store. */
    private static Preferences prefs() {
        return Preferences.userNodeForPackage(Gui.class);
    }

    private Gui() {
    }

    /**
     * True when a window can really be shown. A DISPLAY variable is not enough:
     * over SSH it can point to a display that does not answer, and opening the
     * window would then fail in the background and leave the program hanging.
     */
    static boolean available() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return false;
        try {
            java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length > 0;
        } catch (Throwable e) { // AWTError, HeadlessException, UnsatisfiedLinkError…
            return false;
        }
    }

    /** Opens the window; returns at once (the Swing thread keeps the program running). */
    static void open(List<Server> servers) {
        SwingUtilities.invokeLater(() -> {
            applyTheme(prefs().getBoolean("dark", true));
            JFrame frame = new JFrame("PingPlanet");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setIconImages(List.of(GuiKit.planet(16), GuiKit.planet(32), GuiKit.planet(64), GuiKit.planet(128)));
            frame.setContentPane(content(frame, servers));
            frame.setSize(new Dimension(1180, 860));
            frame.setMinimumSize(new Dimension(900, 640));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    /** Everything inside the window. Also used to render screenshots in tests. */
    static JPanel content(JFrame frame, List<Server> servers) {
        List<Net.Source> sources = Net.internetSources();
        String defaultName = Engine.defaultName(Net.INTERNET).orElse(null);

        JPanel root = new JPanel(new BorderLayout());
        root.add(header(frame), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty("JTabbedPane.tabHeight", 38);
        tabs.addTab("Internet", new InternetPanel(servers, sources, defaultName));
        tabs.addTab("LAN", new LanPanel(sources, defaultName));
        tabs.addTab("Server", new ServerPanel());
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    private static JComponent header(JFrame frame) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 6, 16));
        header.add(new Title(), BorderLayout.CENTER);
        JButton theme = new JButton(FlatLaf.isLafDark() ? "☀ Light" : "☾ Dark");
        theme.setToolTipText("Switch between light and dark");
        theme.addActionListener(e -> {
            boolean dark = !FlatLaf.isLafDark();
            prefs().putBoolean("dark", dark);
            applyTheme(dark);
            if (frame != null) SwingUtilities.updateComponentTreeUI(frame);
            theme.setText(dark ? "☀ Light" : "☾ Dark");
        });
        JButton shot = new JButton("📷 Screenshot");
        shot.setToolTipText("Save the window as a picture (Ctrl+Shift+S)");
        shot.addActionListener(e -> {
            if (frame != null) screenshot(frame);
        });
        if (frame != null) {
            frame.getRootPane().registerKeyboardAction(e -> screenshot(frame),
                    KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(shot);
        buttons.add(theme);
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(buttons, BorderLayout.NORTH);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    /** Saves the window to ~/Pictures and says where, with a way to open the folder. */
    private static void screenshot(JFrame frame) {
        Path file;
        try {
            file = Screenshot.save(frame.getContentPane(), Screenshot.defaultFolder(), LocalDateTime.now());
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(frame, "Could not save the screenshot: " + e.getMessage(), "PingPlanet",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        Object[] options = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
                ? new Object[] {"Open folder", "Copy path", "OK"} : new Object[] {"Copy path", "OK"};
        int choice = JOptionPane.showOptionDialog(frame, "Screenshot saved to\n" + file, "PingPlanet",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[options.length - 1]);
        if (choice < 0) return;
        String picked = (String) options[choice];
        if (picked.equals("Copy path")) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(file.toString()), null);
        } else if (picked.equals("Open folder")) {
            try {
                Desktop.getDesktop().open(file.getParent().toFile());
            } catch (IOException | RuntimeException e) {
                // the path is in the message already
            }
        }
    }

    static void applyTheme(boolean dark) {
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("ScrollBar.thumbArc", 999);
        if (dark) FlatDarkLaf.setup();
        else FlatLightLaf.setup();
    }

    /** Planet icon, the name in the brand gradient, and the tagline. */
    private static final class Title extends JComponent {

        private final BufferedImage icon = GuiKit.planet(44);

        Title() {
            setPreferredSize(new Dimension(500, 52));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = GuiKit.smooth(g);
            g2.drawImage(icon, 0, 4, null);
            Font big = GuiKit.bigFont(26f);
            g2.setFont(big);
            FontMetrics fm = g2.getFontMetrics();
            String name = "PingPlanet";
            int x = 56;
            int w = fm.stringWidth(name);
            g2.setPaint(new GradientPaint(x, 0, GuiKit.CYAN, x + w, 0, GuiKit.PINK));
            g2.drawString(name, x, 30);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12.5f));
            g2.setColor(GuiKit.muted());
            g2.drawString("speed test for your network and the planet · v" + PingPlanet.version(), x + 1, 48);
            g2.dispose();
        }
    }
}
