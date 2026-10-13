package pingplanet;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

/** The Server tab: lets other machines run a LAN test against this one. */
final class ServerPanel extends JPanel {

    private final JSpinner port = GuiKit.spinner(Lan.DEFAULT_PORT, 1, 65535);
    private final JButton toggle = GuiKit.primary("Start server");
    private final JLabel state = new JLabel();
    private final JPanel commands = new JPanel();
    private final DefaultListModel<String> log = new DefaultListModel<>();
    private ServerSocket socket;

    ServerPanel() {
        super(new BorderLayout(0, 14));
        setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        JPanel left = GuiKit.row(GuiKit.caption("Port"), port, state);
        top.add(left, BorderLayout.CENTER);
        toggle.setPreferredSize(new Dimension(140, 36));
        top.add(toggle, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        commands.setLayout(new BoxLayout(commands, BoxLayout.Y_AXIS));
        commands.setOpaque(false);
        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.setOpaque(false);
        JLabel how = new JLabel("<html>While the server runs, another machine on this network can test the link to this one:"
                + " open PingPlanet there, go to the LAN tab and enter one of these addresses, or run:</html>");
        middle.add(how, BorderLayout.NORTH);
        middle.add(commands, BorderLayout.CENTER);

        JList<String> list = new JList<>(log);
        list.setFont(GuiKit.mono());
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(400, 220));
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        JLabel title = new JLabel("Incoming tests");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        bottom.add(title, BorderLayout.NORTH);
        bottom.add(scroll, BorderLayout.CENTER);
        JPanel body = new JPanel(new BorderLayout(0, 18)); // addresses on top, the log takes the rest
        body.setOpaque(false);
        body.add(middle, BorderLayout.NORTH);
        body.add(bottom, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        stopped(null);
        toggle.addActionListener(e -> {
            if (socket == null) begin();
            else end();
        });
    }

    private void begin() {
        int p = (Integer) port.getValue();
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(p));
        } catch (IOException e) {
            stopped("Could not listen on port " + p + ": " + Engine.message(e));
            return;
        }
        socket = server;
        port.setEnabled(false);
        toggle.setText("Stop server");
        toggle.putClientProperty("JButton.buttonType", null);
        state.setText("● Running on port " + p);
        state.setForeground(GuiKit.GREEN);
        showCommands(p);
        Thread.ofPlatform().daemon().name("pingplanet-server").start(() -> {
            try {
                Lan.serve(server, peer -> SwingUtilities.invokeLater(() -> log.add(0,
                        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "   " + peer + " started a test")));
                SwingUtilities.invokeLater(() -> stopped(null));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> stopped("Server stopped: " + Engine.message(e)));
            }
        });
    }

    private void end() {
        try {
            socket.close(); // makes Lan.serve return
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private void stopped(String problem) {
        socket = null;
        port.setEnabled(true);
        toggle.setText("Start server");
        toggle.putClientProperty("JButton.buttonType", "default");
        state.setText(problem != null ? problem : "Stopped");
        state.setForeground(problem != null ? GuiKit.RED : GuiKit.muted());
        showCommands((Integer) port.getValue());
    }

    private void showCommands(int p) {
        commands.removeAll();
        List<Net.Source> sources = Net.sources();
        String suffix = p == Lan.DEFAULT_PORT ? "" : ":" + p;
        if (sources.isEmpty()) commands.add(commandRow("pingplanet lan <this machine's IP>" + suffix, null));
        for (Net.Source s : sources) {
            commands.add(commandRow("pingplanet lan " + s.address().getHostAddress() + suffix, s.name() + " · " + s.displayName()));
            commands.add(Box.createVerticalStrut(6));
        }
        commands.revalidate();
        commands.repaint();
    }

    private JPanel commandRow(String command, String note) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = GuiKit.smooth(g);
                g2.setColor(GuiKit.card());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JLabel text = new JLabel(command);
        text.setFont(GuiKit.mono().deriveFont(14f));
        text.setForeground(GuiKit.CYAN);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(command), null);
            copy.setText("Copied");
        });
        row.add(text);
        row.add(copy);
        if (note != null) {
            JLabel n = GuiKit.caption(note);
            row.add(n);
        }
        return row;
    }

    /** Color of the state label, for tests. */
    Color stateColor() {
        return state.getForeground();
    }
}
