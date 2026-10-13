package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The window's panels, built and clicked without a display (headless). */
class GuiTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
        Gui.applyTheme(true);
    }

    @Test
    void windowContentBuildsAndPaints() throws Exception {
        JPanel[] root = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            root[0] = Gui.content(null, Server.builtIn());
            root[0].setSize(1180, 860);
            root[0].addNotify();
        });
        JTabbedPane tabs = find(root[0], JTabbedPane.class, null);
        assertEquals(List.of("Internet", "LAN", "Server"), List.of(tabs.getTitleAt(0), tabs.getTitleAt(1), tabs.getTitleAt(2)));
        for (int i = 0; i < 3; i++) {
            int tab = i;
            SwingUtilities.invokeAndWait(() -> {
                tabs.setSelectedIndex(tab);
                paint(root[0]);
            });
        }
    }

    @Test
    void connectionPickerChoosesSources() throws Exception {
        Net.Source eth = new Net.Source("eth0", "Ethernet", InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlan0", "Wi-Fi", InetAddress.getByName("192.168.0.23"));
        assertEquals(Collections.singletonList(null), new GuiKit.ConnectionPicker(List.of(), null).selected());
        assertEquals(Collections.singletonList(null), new GuiKit.ConnectionPicker(List.of(eth), "eth0").selected());

        GuiKit.ConnectionPicker picker = new GuiKit.ConnectionPicker(List.of(eth, wifi), "eth0");
        assertEquals(List.of(eth, wifi), picker.selected());
        AbstractButton wifiBox = find(picker, AbstractButton.class, "wlan0");
        assertTrue(find(picker, AbstractButton.class, "eth0 (default)") != null, "the default is marked");
        wifiBox.setSelected(false);
        assertEquals(List.of(eth), picker.selected());
    }

    @Test
    void resultsTableShowsLiveThenFinalRows() {
        Server br = new Server("BR", "Brazil", "São Paulo", "Vultr", -23.55, -46.63, URI.create("https://a.example/f"));
        InternetPanel.ServerTableModel model = new InternetPanel.ServerTableModel(true);
        model.live(br, new Latency(List.of(3.0), 0), Double.NaN);
        model.live(br, new Latency(List.of(3.0), 0), 120.0);
        assertEquals(1, model.getRowCount());
        InternetPanel.ServerTableModel.Row row = (InternetPanel.ServerTableModel.Row) model.getValueAt(0, 0);
        assertTrue(row.testing);
        assertEquals(120.0, row.liveMbps);
        model.done(new Report.ServerResult(br, new Latency(List.of(3.0), 0), new Throughput(75_000_000, 5_000_000_000L), null));
        assertEquals(1, model.getRowCount());
        assertTrue(!row.testing && row.result != null);
        assertEquals(6, model.getColumnCount());
        assertEquals(5, new InternetPanel.ServerTableModel(false).getColumnCount()); // latency only: no Download column
    }

    @Test
    void lanTabRunsATestAgainstALocalServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    Lan.serve(server, peer -> { });
                } catch (IOException ignored) {
                    // closed at the end of the test
                }
            });
            LanPanel[] lan = new LanPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                lan[0] = new LanPanel(List.of(), null);
                lan[0].setSize(1100, 700);
                lan[0].addNotify();
                find(lan[0], JTextField.class, null).setText("127.0.0.1");
                List<JSpinner> spinners = all(lan[0], JSpinner.class);
                spinners.get(0).setValue(server.getLocalPort()); // port
                spinners.get(1).setValue(1);                     // seconds
                find(lan[0], JButton.class, "Start test").doClick();
            });
            long until = System.nanoTime() + 20_000_000_000L;
            while (lan[0].rows().isEmpty() && System.nanoTime() < until) Thread.sleep(100);
            List<Object> row = lan[0].rows().getFirst();
            assertEquals("default", row.get(0));
            assertTrue(row.get(3).toString().endsWith("bps"), row.toString()); // download measured
            SwingUtilities.invokeAndWait(() -> paint(lan[0]));
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static void paint(Component c) {
        layout(c);
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component x : k.getComponents()) layout(x);
        }
    }

    static <T extends Component> T find(Container c, Class<T> type, String text) {
        List<T> all = all(c, type);
        for (T t : all) {
            if (text == null || (t instanceof AbstractButton b && text.equals(b.getText()))) return t;
        }
        return null;
    }

    static <T extends Component> List<T> all(Container c, Class<T> type) {
        List<T> found = new java.util.ArrayList<>();
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) found.add(type.cast(k));
            if (k instanceof Container cc) found.addAll(all(cc, type));
        }
        return found;
    }
}
