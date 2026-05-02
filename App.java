package com.oguieu.checksybasestats;

import com.oguieu.checksybasestats.db.StatBlobParser;
import com.oguieu.checksybasestats.db.SybaseStatisticsDao;
import com.oguieu.checksybasestats.model.HistogramData;
import com.oguieu.checksybasestats.model.HistogramStep;
import com.oguieu.checksybasestats.model.StatEntry;
import com.oguieu.checksybasestats.ui.ConnectionDialog;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.StackedBarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class App extends JFrame {

    private SybaseStatisticsDao dao;
    private String              currentTable;

    // ---- controls ----
    private final JLabel                    connectionLabel = new JLabel("Not connected");
    private final JButton                   connectBtn      = new JButton("Connect…");
    private final JComboBox<String>         tableCombo      = new JComboBox<>();
    private final DefaultListModel<StatEntry> statListModel = new DefaultListModel<>();
    private final JList<StatEntry>          statList        = new JList<>(statListModel);
    private final JLabel                    statusLabel     = new JLabel("Ready");
    private final JTextArea                 hexArea         = new JTextArea(6, 80);
    private final JLabel                    stepsLbl        = new JLabel();
    private final JLabel                    densityLbl      = new JLabel();
    private final JLabel                    rowsLbl         = new JLabel();
    private final JLabel                    sampledLbl      = new JLabel();
    private final JPanel                    chartSlot       = new JPanel(new BorderLayout());

    // -------------------------------------------------------------------------

    public App() {
        super("Sybase ASE — Statistics Viewer");
        buildUI();
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                closeDao();
                dispose();
            }
        });
        setSize(1050, 700);
        setLocationRelativeTo(null);
    }

    // -------------------------------------------------------------------------
    //  Scene construction
    // -------------------------------------------------------------------------

    private void buildUI() {
        // Connection bar
        connectBtn.addActionListener(e -> openConnectDialog());
        JPanel connBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        connBar.setBackground(new Color(0xF0F0F0));
        connBar.add(connectBtn);
        JSeparator vSep = new JSeparator(JSeparator.VERTICAL);
        vSep.setPreferredSize(new Dimension(1, 20));
        connBar.add(vSep);
        connBar.add(connectionLabel);

        // Table selector bar
        tableCombo.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        tableCombo.setEnabled(false);
        tableCombo.addActionListener(e -> {
            String sel = (String) tableCombo.getSelectedItem();
            if (sel != null && !sel.isEmpty()) loadStats(sel);
        });
        JPanel tableBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        tableBar.add(new JLabel("Table:"));
        tableBar.add(tableCombo);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(connBar);
        top.add(new JSeparator());
        top.add(tableBar);

        // Left panel — stat list
        statList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        statList.setCellRenderer(new StatEntryRenderer());
        statList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                StatEntry sel = statList.getSelectedValue();
                if (sel != null) onStatSelected(sel);
            }
        });
        JScrollPane listScroll = new JScrollPane(statList);
        listScroll.setPreferredSize(new Dimension(230, 0));
        JLabel listTitle = new JLabel("Statistics:");
        listTitle.setFont(listTitle.getFont().deriveFont(Font.BOLD));
        listTitle.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        left.add(listTitle,  BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);

        // Right panel — chart + meta + hex
        showPlaceholder();

        JPanel metaBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 4));
        metaBar.setBackground(new Color(0xEBEBEB));
        metaBar.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Color.LIGHT_GRAY));
        metaBar.add(metaPair("Steps:",   stepsLbl));
        metaBar.add(metaPair("Density:", densityLbl));
        metaBar.add(metaPair("Rows:",    rowsLbl));
        metaBar.add(metaPair("Sampled:", sampledLbl));

        hexArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        hexArea.setEditable(false);
        JLabel hexTitle = new JLabel("Raw statblob — first 128 bytes (hex)");
        hexTitle.setFont(hexTitle.getFont().deriveFont(11f));
        hexTitle.setForeground(Color.GRAY);
        hexTitle.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        JPanel hexBox = new JPanel(new BorderLayout());
        hexBox.add(hexTitle,              BorderLayout.NORTH);
        hexBox.add(new JScrollPane(hexArea), BorderLayout.CENTER);

        JPanel bottomRight = new JPanel();
        bottomRight.setLayout(new BoxLayout(bottomRight, BoxLayout.Y_AXIS));
        bottomRight.add(metaBar);
        bottomRight.add(hexBox);

        JPanel right = new JPanel(new BorderLayout());
        right.add(chartSlot,   BorderLayout.CENTER);
        right.add(bottomRight, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(240);

        // Status bar
        statusLabel.setOpaque(true);
        statusLabel.setBackground(new Color(0xF8F8F8));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));

        setLayout(new BorderLayout());
        add(top,         BorderLayout.NORTH);
        add(split,       BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private static JPanel metaPair(String key, JLabel value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel k = new JLabel(key);
        k.setFont(k.getFont().deriveFont(Font.BOLD));
        p.add(k);
        p.add(value);
        return p;
    }

    // -------------------------------------------------------------------------
    //  Actions
    // -------------------------------------------------------------------------

    private void openConnectDialog() {
        ConnectionDialog dlg = new ConnectionDialog(this);
        com.oguieu.checksybasestats.db.ConnectionConfig cfg = dlg.showDialog();
        if (cfg == null) return;

        closeDao();
        status("Connecting to " + cfg.host() + ":" + cfg.port() + "/" + cfg.database() + " …");
        runTask(
                () -> new SybaseStatisticsDao(cfg),
                newDao -> {
                    dao = newDao;
                    connectionLabel.setText(cfg.host() + ":" + cfg.port() + "/" + cfg.database());
                    status("Connected. Loading tables…");
                    loadTableList();
                },
                ex -> {
                    status("Connection failed: " + ex.getMessage());
                    showError("Connection error", ex.getMessage());
                });
    }

    private void loadTableList() {
        runTask(
                () -> dao.loadTableNames(),
                tables -> {
                    String db = dao.getCurrentDatabase();
                    tableCombo.setEnabled(true);
                    if (tables.isEmpty()) {
                        status("Connected (db_name()=" + db + ") — no user tables found.");
                        JOptionPane.showMessageDialog(this,
                                dao.getDiagnosticInfo()
                                + "\nIf db_name() is 'master', the USE statement is being ignored."
                                + "\nIf db_name() is 'testdb' and type 'U' count is 0,"
                                + "\nre-run setup-test-db.sh to recreate the table.",
                                "No tables found", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    // Populate combo — adding the first item auto-selects it and fires
                    // the action listener, which in turn calls loadStats.
                    tableCombo.removeAllItems();
                    for (String t : tables) tableCombo.addItem(t);
                    status(tables.size() + " table(s) in " + db + ". Select one to view its statistics.");
                },
                ex -> {
                    status("Failed to list tables: " + ex.getMessage());
                    showError("Error", ex.getMessage());
                });
    }

    private void loadStats(String table) {
        if (dao == null) return;
        currentTable = table;
        statListModel.clear();
        showPlaceholder();
        hexArea.setText("");
        clearMeta();
        status("Loading statistics for " + table + " …");

        runTask(
                () -> dao.loadStatEntries(table),
                entries -> {
                    if (entries.isEmpty()) {
                        status("No statistics for " + table + "  (run UPDATE STATISTICS first?)");
                        return;
                    }
                    for (StatEntry e : entries) statListModel.addElement(e);
                    status(entries.size() + " statistic(s) loaded for " + table);
                    if (statListModel.getSize() > 0) statList.setSelectedIndex(0);
                },
                ex -> {
                    status("Error: " + ex.getMessage());
                    showError("Load error", ex.getMessage());
                });
    }

    private void onStatSelected(StatEntry entry) {
        hexArea.setText(entry.statblob() != null
                ? StatBlobParser.hexDump(entry.statblob(), 128)
                : "(null statblob)");
        status("Parsing histogram for " + entry.label() + " …");

        runTask(
                () -> dao.buildHistogram(currentTable, entry),
                data -> {
                    displayHistogram(data);
                    stepsLbl.setText(String.valueOf(data.steps()));
                    densityLbl.setText(String.format("%.6f", data.density()));
                    rowsLbl.setText(String.format("%.0f", data.totalRows()));
                    sampledLbl.setText(String.format("%.0f", data.rowsSampled()));
                    status(String.format("%s — %d steps | density %.6f | rows %.0f (sampled %.0f)",
                            entry.label(), data.steps(), data.density(),
                            data.totalRows(), data.rowsSampled()));
                },
                ex -> {
                    status("Parse error: " + ex.getMessage());
                    showError("Parse error",
                            "Failed to parse statblob:\n" + ex.getMessage()
                            + "\n\nCheck the hex dump — format offset may need adjusting.");
                });
    }

    // -------------------------------------------------------------------------
    //  Histogram rendering
    // -------------------------------------------------------------------------

    private void displayHistogram(HistogramData data) {
        chartSlot.removeAll();
        if (data.histogramSteps().isEmpty()) {
            showPlaceholder();
            return;
        }

        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        for (HistogramStep step : data.histogramSteps()) {
            String lbl = abbreviated(step.stepValue(), 14);
            dataset.addValue(step.eqRows(),    "EQ rows",    lbl);
            dataset.addValue(step.rangeRows(), "RANGE rows", lbl);
        }

        JFreeChart chart = ChartFactory.createStackedBarChart(
                data.tableName() + "  —  " + data.label(),
                null, "Row count",
                dataset, PlotOrientation.VERTICAL,
                true, false, false);

        CategoryPlot plot = chart.getCategoryPlot();
        StackedBarRenderer renderer = (StackedBarRenderer) plot.getRenderer();
        renderer.setSeriesPaint(0, new Color(0x4682b4));
        renderer.setSeriesPaint(1, new Color(0x87ceeb));
        renderer.setShadowVisible(false);

        int n = data.histogramSteps().size();
        CategoryAxis axis = plot.getDomainAxis();
        if (n > 20) axis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);
        if (n > 50) axis.setTickLabelsVisible(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setMouseWheelEnabled(true);

        if (n > 50) {
            chartPanel.setPreferredSize(new Dimension(n * 14, 400));
            JScrollPane scroll = new JScrollPane(chartPanel,
                    JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            chartSlot.add(scroll, BorderLayout.CENTER);
        } else {
            chartSlot.add(chartPanel, BorderLayout.CENTER);
        }
        chartSlot.revalidate();
        chartSlot.repaint();
    }

    private void showPlaceholder() {
        chartSlot.removeAll();
        JLabel lbl = new JLabel("Select a statistic from the list", JLabel.CENTER);
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(13f));
        chartSlot.add(lbl, BorderLayout.CENTER);
        chartSlot.revalidate();
        chartSlot.repaint();
    }

    private void clearMeta() {
        stepsLbl.setText("");
        densityLbl.setText("");
        rowsLbl.setText("");
        sampledLbl.setText("");
    }

    // -------------------------------------------------------------------------
    //  Utilities
    // -------------------------------------------------------------------------

    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private <T> void runTask(final ThrowingSupplier<T> work,
                              final Consumer<T> onSuccess,
                              final Consumer<Throwable> onError) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.get(); }
            @Override protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    onError.accept(cause != null ? cause : ex);
                }
            }
        }.execute();
    }

    private void status(String msg) { statusLabel.setText(msg); }

    private void closeDao() {
        if (dao == null) return;
        try { dao.close(); } catch (Exception ignored) {}
        dao = null;
        connectionLabel.setText("Not connected");
        tableCombo.removeAllItems();
        tableCombo.setEnabled(false);
    }

    private void showError(String title, String msg) {
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE);
    }

    private static String abbreviated(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // -------------------------------------------------------------------------
    //  List cell renderer
    // -------------------------------------------------------------------------

    private static class StatEntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (!(value instanceof StatEntry)) return this;
            StatEntry item = (StatEntry) value;

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(true);
            panel.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

            String display = (item.label() != null && !item.label().isEmpty())
                    ? item.label() : "stat#" + item.statId();
            JLabel name = new JLabel(display);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());

            JLabel meta = new JLabel(String.format("indid=%d  steps=%d  rows=%.0f",
                    item.indid(), item.steps(), item.rows()));
            meta.setFont(meta.getFont().deriveFont(10f));
            meta.setForeground(isSelected ? list.getSelectionForeground() : Color.GRAY);

            panel.add(name);
            panel.add(meta);
            return panel;
        }
    }
}
