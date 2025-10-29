package com.BankStats;

import net.runelite.client.ui.PluginPanel;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;

import javax.swing.table.TableCellRenderer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.awt.event.MouseEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import javax.swing.ScrollPaneConstants;
import java.util.Comparator;
import javax.swing.SwingConstants;
import javax.swing.RowFilter;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.nio.file.Paths;



public class BankStatsPanel extends PluginPanel
{
    private final JButton updateBtn = new JButton("Update from bank");
    private final JButton exportBtn = new JButton("Export CSV");
    private final JButton saveNamedSnapBtn = new JButton("Save Named Snapshot");
    private final JLabel status = new JLabel("Click update while bank is open.");
    private final JTable table;
    private final JButton importNamedSnapBtn = new JButton("Import Named Snapshot");
    private static final int ROW_SIDE_PAD = 6;
    private final DefaultTableModel model;
    private final JTextField searchField = new JTextField(24);
    private TableRowSorter<DefaultTableModel> mainSorter;
    private TableRowSorter<DefaultTableModel> detailSorter;
    private TableRowSorter<DefaultTableModel> snapSorter;
    private final JPanel netSummaryBox = new JPanel(new BorderLayout());
    private final JLabel netSummaryLabel = new JLabel("Net: -");
    private boolean revealNetBoxOnNextCompute = false; // show only after Compare
    private final JTable detailTable;
    private final DefaultTableModel detailModel;
    private final Runnable onUpdate;
    private final JButton distancesPopupBtn = new JButton("Price Data Popup Window");
    private final JButton gainLossPopupBtn = new JButton("GainLoss");
    // Singleton dialog refs so we never open duplicates
    private JDialog distancesDlg;
    private JDialog gainLossDlg;
    final Insets tight = new Insets(2, 8, 2, 8);
    private final DefaultTableModel snapshotModel = new DefaultTableModel(
            new Object[]{"Item", "Net Gain or Loss", "Percentage Change", "Total Net"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
        @Override public Class<?> getColumnClass(int c) {
            switch (c) {
                case 0: return String.class;   // Item
                case 1: return Integer.class;  // Net gain/loss (qty * per-unit Δ)
                case 2: return Double.class;   // Percentage Change
                case 3: return Integer.class;  // Total Net (only one cell shown)
                default: return Object.class;
            }
        }
    };

    //paints the net box when user compares
    private void refreshNetSummaryBox()
    {
        SwingUtilities.invokeLater(() -> {
            Integer total = snapshotGrandTotal;
            if (total == null) {
                netSummaryBox.setVisible(false);
                return;
            }

            netSummaryLabel.setText("Net: " + fmtKM(total));

            // choose colors
            final boolean positive = total > 0;
            final boolean zero = total == 0;
            Color bg, border, fg = Color.WHITE;

            if (positive) {
                bg = new Color(34, 94, 52);     // green
                border = new Color(52, 128, 72);
            } else if (zero) {
                bg = new Color(70, 70, 70);     // neutral gray
                border = new Color(90, 90, 90);
            } else {
                bg = new Color(120, 45, 45);    // red
                border = new Color(150, 60, 60);
            }

            netSummaryBox.setBackground(bg);
            netSummaryBox.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(border, 1),      // minimal border
                    BorderFactory.createEmptyBorder(0, 0, 0, 0)
            ));
            netSummaryLabel.setForeground(fg);

            netSummaryBox.setVisible(true);
            netSummaryBox.revalidate();
            netSummaryBox.repaint();

            // only auto-reveal once per Compare press
            revealNetBoxOnNextCompute = false;
        });
    }
    // --- small helpers for subtle striping & sizing ---
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    private static Color nudge(Color c, int delta) {
        return new Color(clamp(c.getRed()+delta), clamp(c.getGreen()+delta), clamp(c.getBlue()+delta));
    }
    private static int rowHeightFor(JTable t) {
        FontMetrics fm = t.getFontMetrics(t.getFont());
        return Math.max(18, fm.getHeight() + 4); // compact, but readable
    }
    private final JTable snapshotTable = new JTable(snapshotModel) {
        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            Component c = super.prepareRenderer(renderer, row, column);
            if (!isRowSelected(row)) {
                Color base = getBackground();
                int nudge = (row % 2 == 0) ? 6 : -6;
                c.setBackground(new Color(
                        Math.max(0, Math.min(255, base.getRed()   + nudge)),
                        Math.max(0, Math.min(255, base.getGreen() + nudge)),
                        Math.max(0, Math.min(255, base.getBlue()  + nudge))
                ));
                c.setForeground(getForeground());
            }
            return c;
        }
        @Override
        public String getToolTipText(MouseEvent e) {
            java.awt.Point p = e.getPoint();
            int viewRow = rowAtPoint(p);
            int viewCol = columnAtPoint(p);
            if (viewRow < 0 || viewCol < 0) return null;

            // use model row for the name, respect current sorting/filtering
            int modelRow = convertRowIndexToModel(viewRow);

            Object nameObj = snapshotModel.getValueAt(modelRow, 0); // "Item"
            String itemName = (nameObj == null) ? "" : nameObj.toString();

            Object value = getValueAt(viewRow, viewCol);
            String valStr;
            if (value == null) {
                valStr = "-";
            } else if (value instanceof Integer) {
                // integers (Net & Total): full commas
                valStr = NumberFormat.getIntegerInstance(Locale.US)
                        .format(((Integer) value).intValue());
            } else if (value instanceof Double) {
                // % column
                valStr = new java.text.DecimalFormat("0.0%")
                        .format(((Double) value).doubleValue());
            } else {
                valStr = value.toString();
            }
            return itemName + " = " + valStr;
        }

    };

    private final JButton snapshotBtn = new JButton("Snapshot");
    private final JButton importBtn   = new JButton("Import Default");
    private final JButton comparePopupBtn = new JButton("Compare Data Popup Window");

    private JDialog myItemsDlg; // popup for the snapshot table

    private final BankStatsPlugin plugin;

    private Integer snapshotGrandTotal = null; // grand total (clamped to int for display)



    private java.util.List<BankStatsPlugin.Row> backingRows = new ArrayList<>();

    private void updateFilters()
    {
        String text = searchField.getText();
        RowFilter<DefaultTableModel, Object> rf = null;

        if (text != null) {
            text = text.trim();
            if (!text.isEmpty()) {
                // Case-insensitive match on the Item column (model col 0)
                rf = RowFilter.regexFilter("(?i)" + Pattern.quote(text), 0);
            }
        }

        if (mainSorter != null)   mainSorter.setRowFilter(rf);
        if (detailSorter != null) detailSorter.setRowFilter(rf);

        if (snapSorter != null)   snapSorter.setRowFilter(rf); // ← add this line
    }


    // Read ~/.bank-prices/snapshot.csv, fetch current highs, compute (snapshot - current), fill table
    private void importSnapshotAndCompute()
    {
        java.nio.file.Path file = Paths.get(System.getProperty("user.home"), ".bank-prices", "snapshot.csv");
        if (!Files.exists(file)) {
            setStatus("No snapshot found. Click Snapshot first.");
            return;
        }

        // 1) Read id -> (name, snapHigh)
        java.util.Map<Integer, String> idToName = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> idToSnap = new java.util.HashMap<>();
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();

        try (java.io.BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null)
            {
                String[] parts = line.split(",", 3); // id,name,currentHigh
                if (parts.length < 3) continue;
                try {
                    int id = Integer.parseInt(parts[0].trim());
                    String name = parts[1].trim();
                    int snap = Integer.parseInt(parts[2].trim());
                    ids.add(id);
                    idToName.put(id, name);
                    idToSnap.put(id, snap);
                } catch (NumberFormatException ignored) {
                    // skip bad rows
                }
            }
        }
        catch (IOException ex) {
            setStatus("Import failed: " + ex.getMessage());
            return;
        }

        if (ids.isEmpty()) {
            setStatus("Snapshot file was empty.");
            return;
        }

        setStatus("Importing " + ids.size() + " items...");

        // 2) Ask the plugin to fetch latest for all ids (bulk, async)
        plugin.fetchLatestForIdsAsync(ids, latestMap -> {
    snapshotModel.setRowCount(0);

    // quantity map from latest bank snapshot (if available)
    java.util.Map<Integer, Integer> idToQty = new java.util.HashMap<>();
    if (backingRows != null) {
        for (BankStatsPlugin.Row r : backingRows) {
            if (r != null && r.qty != null && r.qty > 0) idToQty.put(r.id, r.qty);
        }
    }

    long grand = 0L;

    for (int id : ids) {
        Integer snap = idToSnap.get(id);
        Integer cur  = latestMap.get(id);
        if (snap == null || cur == null) continue;

        // per-unit Δ
        int perUnitDelta = cur - snap;

        // % change
        Double pct = (snap != 0) ? ((cur - snap) / (double) snap) : null;

        // net Δ (qty * perUnitDelta)
        Integer qty = idToQty.get(id);
        Integer net = null;
        if (qty != null) {
            long v = (long) qty * (long) perUnitDelta;
            if      (v > Integer.MAX_VALUE) net = Integer.MAX_VALUE;
            else if (v < Integer.MIN_VALUE) net = Integer.MIN_VALUE;
            else                             net = (int) v;
        }

        if (net != null) grand += net;

        String name = idToName.getOrDefault(id, "Item " + id);
        // col0 Item, col1 Net (qty*Δ), col2 % change, col3 Total (left null; renderer will show only at top row)
        snapshotModel.addRow(new Object[]{ name, net, pct, null });
    }

    // clamp and store grand total for renderer
    if      (grand > Integer.MAX_VALUE) snapshotGrandTotal = Integer.MAX_VALUE;
    else if (grand < Integer.MIN_VALUE) snapshotGrandTotal = Integer.MIN_VALUE;
    else                                snapshotGrandTotal = (int) grand;

    setStatus("Import complete: " + snapshotModel.getRowCount() + " items compared.");

    // repaint tables so the “Total Net” single cell updates immediately
    snapshotTable.repaint();
    if (myItemsDlg != null && myItemsDlg.isShowing()) myItemsDlg.repaint();
            // Update the colored Net chip now that totals are computed
            if (revealNetBoxOnNextCompute || netSummaryBox.isVisible()) {
                refreshNetSummaryBox();
            }
        });



    }

    // Open a file picker in ~/.bank-prices and import the chosen snapshot
    private void importNamedSnapshotAndComputeViaChooser()
    {
        Path dir = Paths.get(System.getProperty("user.home"), ".bank-prices");
        try { Files.createDirectories(dir); } catch (IOException ignore) {}

        JFileChooser fc = new JFileChooser(dir.toFile());
        fc.setDialogTitle("Open Snapshot");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));

        int choice = fc.showOpenDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) {
            setStatus("Import canceled.");
            return;
        }

        Path file = fc.getSelectedFile().toPath();
        importSnapshotFileAndCompute(file);
    }

    // Core loader used by the chooser (same format as your Snap files)
    private void importSnapshotFileAndCompute(Path file)
    {
        if (file == null || !Files.exists(file)) {
            setStatus("Snapshot not found.");
            return;
        }

        // 1) Read id -> (name, snapshot high)
        Map<Integer, String> idToName = new HashMap<>();
        Map<Integer, Integer> idToSnap = new HashMap<>();
        Set<Integer> ids = new LinkedHashSet<>();

        try (java.io.BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null)
            {
                String[] parts = line.split(",", 3); // id,name,currentHigh
                if (parts.length < 3) continue;
                try {
                    int id = Integer.parseInt(parts[0].trim());
                    String name = parts[1].trim();
                    int snap = Integer.parseInt(parts[2].trim());
                    ids.add(id);
                    idToName.put(id, name);
                    idToSnap.put(id, snap);
                } catch (NumberFormatException ignored) {}
            }
        }
        catch (IOException ex) {
            setStatus("Import failed: " + ex.getMessage());
            return;
        }

        if (ids.isEmpty()) {
            setStatus("Snapshot file was empty.");
            return;
        }

        setStatus("Importing " + ids.size() + " items from " + file.getFileName() + "...");

        // 2) Fetch current highs for all ids (bulk/async) then compute
        plugin.fetchLatestForIdsAsync(ids, latestMap -> {
            snapshotModel.setRowCount(0);

            // quantity map from the latest bank snapshot we have
            Map<Integer, Integer> idToQty = new HashMap<>();
            if (backingRows != null) {
                for (BankStatsPlugin.Row r : backingRows) {
                    if (r != null && r.qty != null && r.qty > 0) idToQty.put(r.id, r.qty);
                }
            }

            long grand = 0L;

            for (int id : ids) {
                Integer snap = idToSnap.get(id);
                Integer cur  = latestMap.get(id);
                if (snap == null || cur == null) continue;

                int perUnitDelta = cur - snap;
                Double pct = (snap != 0) ? ((cur - snap) / (double) snap) : null;

                Integer qty = idToQty.get(id);
                Integer net = null;
                if (qty != null) {
                    long v = (long) qty * (long) perUnitDelta;
                    if      (v > Integer.MAX_VALUE) net = Integer.MAX_VALUE;
                    else if (v < Integer.MIN_VALUE) net = Integer.MIN_VALUE;
                    else                             net = (int) v;
                }
                if (net != null) grand += net;

                String name = idToName.getOrDefault(id, "Item " + id);
                snapshotModel.addRow(new Object[]{ name, net, pct, null });
            }

            // store grand total for the single-cell renderer & net chip
            if      (grand > Integer.MAX_VALUE) snapshotGrandTotal = Integer.MAX_VALUE;
            else if (grand < Integer.MIN_VALUE) snapshotGrandTotal = Integer.MIN_VALUE;
            else                                snapshotGrandTotal = (int) grand;

            setStatus("Import complete: " + snapshotModel.getRowCount() + " items compared.");

            snapshotTable.repaint();
            if (myItemsDlg != null && myItemsDlg.isShowing()) myItemsDlg.repaint();

            if (revealNetBoxOnNextCompute) {
                refreshNetSummaryBox();   // paint/update the colored Net chip
            }
        });
    }


    // Write ~/.bank-prices/snapshot.csv with: id,name,currentHigh
    private void writeSnapshotToDisk()
    {
        try
        {
            java.nio.file.Path dir  = Paths.get(System.getProperty("user.home"), ".bank-prices");
            java.nio.file.Path file = dir.resolve("snapshot.csv");
            Files.createDirectories(dir);

            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
            {
                w.write("id,name,currentHigh"); w.newLine();
                for (BankStatsPlugin.Row r : backingRows)
                {
                    // Only write rows that have a current price
                    if (r.currentHigh == null) continue;
                    // Very simple CSV (names in OSRS don't have commas, so no fancy escaping needed)
                    w.write(Integer.toString(r.id)); w.write(",");
                    w.write(r.name == null ? "" : r.name); w.write(",");
                    w.write(Integer.toString(r.currentHigh));
                    w.newLine();
                }
            }

            setStatus("Snapshot saved to " + file.toString());
        }
        catch (IOException ex)
        {
            setStatus("Snapshot failed: " + ex.getMessage());
        }
    }

    private void saveNamedSnapshot()
    {
        // Must have data from the last Update
        if (backingRows == null || backingRows.isEmpty()) {
            setStatus("No data to snapshot. Click Update from bank first.");
            return;
        }

        // Ensure folder exists
        Path dir = Paths.get(System.getProperty("user.home"), ".bank-prices");
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            setStatus("Cannot create snapshot folder: " + ex.getMessage());
            return;
        }

        // Default filename (timestamped), default directory = ~/.bank-prices
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        java.io.File defaultFile = dir.resolve("snapshot_" + ts + ".csv").toFile();

        JFileChooser fc = new JFileChooser(dir.toFile());
        fc.setDialogTitle("Save Snapshot As");
        fc.setSelectedFile(defaultFile);
        fc.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));

        int choice = fc.showSaveDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) {
            setStatus("Save canceled.");
            return;
        }

        Path path = fc.getSelectedFile().toPath();
        // enforce .csv extension
        if (!path.toString().toLowerCase().endsWith(".csv")) {
            path = path.resolveSibling(path.getFileName().toString() + ".csv");
        }

        // Write the same CSV format as Snap: id,name,currentHigh
        int written = 0;
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("id,name,currentHigh");
            w.newLine();
            for (com.BankStats.BankStatsPlugin.Row r : backingRows) {
                if (r.currentHigh == null) continue; // skip unpriced
                w.write(Integer.toString(r.id)); w.write(",");
                w.write(r.name == null ? "" : r.name); w.write(",");
                w.write(Integer.toString(r.currentHigh));
                w.newLine();
                written++;
            }
        } catch (IOException ex) {
            setStatus("Save failed: " + ex.getMessage());
            return;
        }

        setStatus("Saved " + written + " rows to " + path.toString());
    }

    private void exportToCsv()
    {
        // Build a default filename with timestamp
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String defName = "bank_prices_" + ts + ".csv";

        // Save dialog
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export Item & Current Price CSV");
        fc.setSelectedFile(new java.io.File(defName));
        fc.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));

        int choice = fc.showSaveDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION)
        {
            setStatus("Export canceled.");
            return;
        }

        Path path = fc.getSelectedFile().toPath();
        // ensure .csv extension
        if (!path.toString().toLowerCase().endsWith(".csv"))
        {
            path = path.resolveSibling(path.getFileName().toString() + ".csv");
        }

        // Write CSV: headers + rows (Item, Current (high))
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
        {
            // header
            w.write("Item,CurrentHigh,Quantity");
            w.newLine();

            // Export in the order the user sees (view order)
            int viewRows = table.getRowCount();
            for (int vr = 0; vr < viewRows; vr++)
            {
                int mr = table.convertRowIndexToModel(vr); // map view row -> model row

                Object nameObj  = model.getValueAt(mr, 0); // Item
                Object priceObj = model.getValueAt(mr, 1); // Current (high)

                // Quantity comes from the backingRows (not a table column)
                int qty = 0;
                if (backingRows != null && mr >= 0 && mr < backingRows.size())
                {
                    com.BankStats.BankStatsPlugin.Row row = backingRows.get(mr);
                    if (row != null && row.qty != null) qty = row.qty;
                }

                String name  = nameObj == null ? "" : nameObj.toString();
                String price = (priceObj instanceof Number) ? String.valueOf(((Number) priceObj).longValue())
                        : (priceObj == null ? "" : priceObj.toString());

                // Write: Item,Quantity,CurrentHigh
                w.write(escapeCsv(name));
                w.write(',');
                w.write(price);
                w.write(',');
                w.write(Integer.toString(qty));
                w.newLine();
            }

        }
        catch (IOException ex)
        {
            setStatus("Export failed: " + ex.getMessage());
            return;
        }

        setStatus("Exported to: " + path.toString());
    }

    /** Escape a value for a CSV field (RFC4180-ish). */
    private static String escapeCsv(String s)
    {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        String doubled = s.replace("\"", "\"\"");
        return "\"" + doubled + "\"";
    }

    /** Format integers as k/m with truncation (handles negatives):
     *   + < 1,000,000 → (abs(value)/1000) + "k", prefixed with "-" if negative
     *   + ≥ 1,000,000 → (abs(value)/1_000_000) + "m", prefixed with "-" if negative
     *   + null → "-"
     *   + values with |value| < 1000 → "0k"
     */
    private static String fmtKM(Integer n)
    {
        if (n == null) return "-";
        int v = n;
        int av = Math.abs(v);

        if (av >= 1_000_000)
        {
            return (v < 0 ? "-" : "") + (av / 1_000_000) + "m";
        }
        if (av >= 1_000)
        {
            return (v < 0 ? "-" : "") + (av / 1_000) + "k";
        }
        return "0k";
    }

    public BankStatsPanel(BankStatsPlugin plugin, Runnable onUpdate)
    {

        this.plugin = plugin;
        this.onUpdate = onUpdate;

        final float BODY_PT = 13f;   // compact body text
        final float HEADER_PT = 12f;

        setLayout(new BorderLayout(6, 6));
        setMinimumSize(new Dimension(0, 0));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        JPanel top = new JPanel(new BorderLayout(6, 6));
        JPanel leftButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        leftButtons.add(updateBtn);
        leftButtons.add(exportBtn);
        top.add(leftButtons, BorderLayout.WEST);
        top.add(status, BorderLayout.CENTER);

        JPanel topStatus = new JPanel(new BorderLayout(6, 6));
        JPanel topStatusIndicator = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        topStatusIndicator.add(status);
        topStatus.add(topStatusIndicator,BorderLayout.WEST);


// --- Search at the very top (under the toolbar) ---

// Horizontal search bar: [Search: ][text field]
        JPanel searchBar = new JPanel(new BorderLayout(8, 0));
        searchBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        searchBar.add(new JLabel("Search:"), BorderLayout.WEST);

        searchField.setToolTipText("Filter by item name (case-insensitive)");
        searchField.setPreferredSize(new Dimension(10, 28));           // compact height
        searchField.putClientProperty("JComponent.minimumHeight", 28);  // keep it from shrinking
        searchBar.add(searchField, BorderLayout.CENTER);

// Stack the existing toolbar (`top`) and the search bar in the NORTH of the panel
        JPanel northStack = new JPanel();
        northernStack:
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.add(top);
        northStack.add(searchBar);
        northStack.add(topStatus);

        add(northStack, BorderLayout.NORTH);



        this.model = new DefaultTableModel(
                new Object[]{"Item", "Current (high)", "7d Low", "7d High", "Gain−Loss"},
                0
        ) {
            @Override public boolean isCellEditable(int row, int col) { return false; }

            // Tell JTable the column types: String for name, Integer for the rest
            @Override public Class<?> getColumnClass(int columnIndex)
            {
                return columnIndex == 0 ? String.class : Integer.class;
            }
        };
        final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        this.table = new JTable(model) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    // very subtle zebra: two tiny nudges from the base bg
                    Color base = getBackground();
                    Color a = nudge(base, +6);
                    Color b = nudge(base, +2);
                    c.setBackground((row % 2 == 0) ? a : b);
                    c.setForeground(getForeground());
                } else {
                    c.setBackground(getSelectionBackground());
                    c.setForeground(getSelectionForeground());
                }
                return c;
            }
            @Override
            public String getToolTipText(MouseEvent e)
            {
                java.awt.Point p = e.getPoint();
                int viewRow = rowAtPoint(p);
                int viewCol = columnAtPoint(p);
                if (viewRow < 0 || viewCol < 0) return null;

                // Convert to model row so we read the correct item even when sorted
                int modelRow = convertRowIndexToModel(viewRow);

                // Item name is always column 0 in the model
                Object nameObj = model.getValueAt(modelRow, 0);
                String itemName = (nameObj == null) ? "" : nameObj.toString();

                Object value = getValueAt(viewRow, viewCol);
                String valStr;
                if (value == null) {
                    valStr = "-";
                } else if (value instanceof Integer) {
                    valStr = nf.format(((Integer) value).intValue()); // full number with commas
                } else if (value instanceof Double) {
                    // Main table normally won’t have Doubles, but handle gracefully
                    valStr = new java.text.DecimalFormat("0.0%").format(((Double) value).doubleValue());
                } else {
                    valStr = value.toString();
                }

                return itemName + " = " + valStr;
            }
        };
        this.table.setToolTipText(""); // enable Swing tooltips

        this.table.setFont(this.table.getFont().deriveFont(BODY_PT));
        JTableHeader th = this.table.getTableHeader();
        th.setFont(th.getFont().deriveFont(HEADER_PT));
        this.table.setRowHeight(rowHeightFor(this.table));
        th.setPreferredSize(new Dimension(0, th.getFontMetrics(th.getFont()).getHeight() + 6));

        this.table.setAutoCreateRowSorter(true); // allow sorting

        // ▼ Optional: enable horizontal scrolling too
        this.table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        this.table.getColumnModel().getColumn(0).setPreferredWidth(220); // Item
        this.table.getColumnModel().getColumn(1).setPreferredWidth(130); // Current (high)
        this.table.getColumnModel().getColumn(2).setPreferredWidth(120); // 7d Low
        this.table.getColumnModel().getColumn(3).setPreferredWidth(120); // 7d High
        this.table.getColumnModel().getColumn(4).setPreferredWidth(160); // Gain−Loss

        DefaultTableCellRenderer intRenderer = new DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                // value is Integer (per getColumnClass); format as k/m
                setText(fmtKM((Integer) value));
            }
            {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }
        };

        // Apply renderer to numeric columns (1..4)
        this.table.getColumnModel().getColumn(1).setCellRenderer(intRenderer);
        this.table.getColumnModel().getColumn(2).setCellRenderer(intRenderer);
        this.table.getColumnModel().getColumn(3).setCellRenderer(intRenderer);
        this.table.getColumnModel().getColumn(4).setCellRenderer(intRenderer);

        snapshotTable.setToolTipText(""); // enable Swing tooltips

        /* ---------- STEP 5: null-safe numeric sorting comparators ---------- */
        mainSorter = (TableRowSorter<DefaultTableModel>) this.table.getRowSorter();
        if (mainSorter != null) {
            Comparator<Integer> nullSafe = Comparator.nullsLast(Integer::compareTo);
            mainSorter.setComparator(1, nullSafe);
            mainSorter.setComparator(2, nullSafe);
            mainSorter.setComparator(3, nullSafe);
            mainSorter.setComparator(4, nullSafe);
        }





        // ─────────────────────────────────────────────────────────────────────
        // SECONDARY SUMMARY TABLE (Item, AvgHighPrice, Dist. to 7d Low, Dist. to 30d Low)
        // ─────────────────────────────────────────────────────────────────────
        this.detailModel = new DefaultTableModel(
                new Object[]{
                        "Item", "AvgHighPrice",
                        "Dist. to 7d Low", "Dist. to 7d High",
                        "Dist. to 30d Low", "Dist. to 30d High",
                        "Dist. to 6mo Low", "Dist. to 6mo High"   // ◀ add
                },
                0
        ) {
            @Override public boolean isCellEditable(int row, int col) { return false; }

            @Override public Class<?> getColumnClass(int columnIndex)
            {
                // 0: name, 1: price (Integer), 2..7: percentages (Double)
                if (columnIndex == 0) return String.class;
                else if (columnIndex == 1) return Integer.class;
                else return Double.class;
            }
        };

        // Cell tooltips (full numbers / percents), Java 11 style
        this.detailTable = new JTable(detailModel) {

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    // subtle row zebra using your nudge() helper
                    Color base = getBackground();
                    c.setBackground((row % 2 == 0) ? nudge(base, +6) : nudge(base, +2));
                    c.setForeground(getForeground());
                } else {
                    c.setBackground(getSelectionBackground());
                    c.setForeground(getSelectionForeground());
                }
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent e)
            {
                java.awt.Point p = e.getPoint();
                int viewRow = rowAtPoint(p);
                int viewCol = columnAtPoint(p);
                if (viewRow < 0 || viewCol < 0) return null;

                int modelRow = convertRowIndexToModel(viewRow);

                // Item name lives in column 0 of the detail model
                Object nameObj = detailModel.getValueAt(modelRow, 0);
                String itemName = (nameObj == null) ? "" : nameObj.toString();

                Object value = getValueAt(viewRow, viewCol);
                String valStr;
                if (value == null) {
                    valStr = "-";
                } else if (value instanceof Integer) {
                    valStr = nf.format(((Integer) value).intValue()); // full number with commas
                } else if (value instanceof Double) {
                    double d = ((Double) value).doubleValue();
                    valStr = new java.text.DecimalFormat("0.0%").format(d); // percent
                } else {
                    valStr = value.toString();
                }

                return itemName + " = " + valStr;
            }
        };
        this.detailTable.setToolTipText(""); // enable Swing tooltips

        this.detailTable.setFont(this.detailTable.getFont().deriveFont(BODY_PT));
        JTableHeader dth = this.detailTable.getTableHeader();
        dth.setFont(dth.getFont().deriveFont(HEADER_PT));
        this.detailTable.setRowHeight(rowHeightFor(this.detailTable));
        dth.setPreferredSize(new Dimension(0, dth.getFontMetrics(dth.getFont()).getHeight() + 6));

        this.detailTable.setAutoCreateRowSorter(true);

        // Adjust row height
        //this.detailTable.setRowHeight(ROW_H);

        // ▼ Enable horizontal scrolling and set widths
        this.detailTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        this.detailTable.getColumnModel().getColumn(0).setPreferredWidth(100); // Item
        this.detailTable.getColumnModel().getColumn(1).setPreferredWidth(100); // AvgHighPrice
        this.detailTable.getColumnModel().getColumn(2).setPreferredWidth(100); // Dist. to 7d Low
        this.detailTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Dist. to 7d High
        this.detailTable.getColumnModel().getColumn(4).setPreferredWidth(100); // Dist. to 30d Low
        this.detailTable.getColumnModel().getColumn(5).setPreferredWidth(100); // Dist. to 30d High
        this.detailTable.getColumnModel().getColumn(6).setPreferredWidth(100); // 6mo Low  ◀ add
        this.detailTable.getColumnModel().getColumn(7).setPreferredWidth(100); // 6mo High ◀ add

        DefaultTableCellRenderer detailIntRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) { setText(fmtKM((Integer) value)); }
            { setHorizontalAlignment(SwingConstants.RIGHT); setOpaque(true); }
        };
        this.detailTable.getColumnModel().getColumn(1).setCellRenderer(detailIntRenderer);

        // Percent renderer for all distance columns
        DefaultTableCellRenderer pctRenderer = new DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                if (value instanceof Double)
                {
                    double d = ((Double) value).doubleValue();
                    setText(new java.text.DecimalFormat("0.0%").format(d));
                }
                else
                {
                    setText(value == null ? "-" : value.toString());
                }
            }
            {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }
        };

        // Apply to all % columns: 2..5
        this.detailTable.getColumnModel().getColumn(2).setCellRenderer(pctRenderer); // 7d Low
        this.detailTable.getColumnModel().getColumn(3).setCellRenderer(pctRenderer); // 7d High
        this.detailTable.getColumnModel().getColumn(4).setCellRenderer(pctRenderer); // 30d Low
        this.detailTable.getColumnModel().getColumn(5).setCellRenderer(pctRenderer); // 30d High
        this.detailTable.getColumnModel().getColumn(6).setCellRenderer(pctRenderer); // ◀ add
        this.detailTable.getColumnModel().getColumn(7).setCellRenderer(pctRenderer);

        // Column header tooltips for the detail table
        JTableHeader dHdr = new JTableHeader(this.detailTable.getColumnModel()) {
            @Override
            public String getToolTipText(MouseEvent e)
            {
                int vCol = columnAtPoint(e.getPoint());
                if (vCol < 0) return null;
                int mCol = detailTable.convertColumnIndexToModel(vCol);

                // Java 11 switch style
                switch (mCol) {
                    case 0: return "Item name";
                    case 1: return "Current price (high) from /latest";
                    case 2: return "Dist. to 7d Low = (current − minMid7) / minMid7";
                    case 3: return "Dist. to 7d High = (maxMid7 − current) / maxMid7";
                    case 4: return "Dist. to 30d Low = (current − minMid30) / minMid30";
                    case 5: return "Dist. to 30d High = (maxMid30 − current) / maxMid30";
                    case 6: return "Dist. to 6mo Low = (current − minMid180) / minMid180";   // ◀ add
                    case 7: return "Dist. to 6mo High = (maxMid180 − current) / maxMid180";
                    default: return null;
                }
            }
        };

        // Set the custom header
        this.detailTable.setTableHeader(dHdr);
        //dHdr.setPreferredSize(new Dimension(0, HEADER_H));

        // Null-safe sorting for detail table
        detailSorter = (TableRowSorter<DefaultTableModel>) this.detailTable.getRowSorter();
        if (detailSorter != null) {
            detailSorter.setComparator(1, Comparator.nullsLast(Integer::compareTo)); // price
            detailSorter.setComparator(2, Comparator.nullsLast(Double::compare));    // % 7d Low
            detailSorter.setComparator(3, Comparator.nullsLast(Double::compare));    // % 7d High
            detailSorter.setComparator(4, Comparator.nullsLast(Double::compare));    // % 30d Low
            detailSorter.setComparator(5, Comparator.nullsLast(Double::compare));    // % 30d High
            detailSorter.setComparator(6, Comparator.nullsLast(Double::compare));    // % 6mo Low  ◀ add
            detailSorter.setComparator(7, Comparator.nullsLast(Double::compare));    // % 6mo High ◀ add
        }
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateFilters(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateFilters(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateFilters(); }
        });

        updateFilters();

// === NEW: Snapshot (My Items) table ===
        snapshotTable.setAutoCreateRowSorter(true);
        snapshotTable.setFont(snapshotTable.getFont().deriveFont(BODY_PT));
        JTableHeader sth = snapshotTable.getTableHeader();
        sth.setFont(sth.getFont().deriveFont(HEADER_PT));
        snapshotTable.setRowHeight(rowHeightFor(snapshotTable));
        sth.setPreferredSize(new Dimension(0, sth.getFontMetrics(sth.getFont()).getHeight() + 6));
        snapshotTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

// widths
        snapshotTable.getColumnModel().getColumn(0).setPreferredWidth(100); // Item
        snapshotTable.getColumnModel().getColumn(1).setPreferredWidth(100); // Net Gain or Loss
        snapshotTable.getColumnModel().getColumn(2).setPreferredWidth(100); // Percentage Change
        snapshotTable.getColumnModel().getColumn(3).setPreferredWidth(100); // Total Net (single-cell)

// renderers
        DefaultTableCellRenderer pctRenderer2 = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (value instanceof Double) setText(new java.text.DecimalFormat("0.0%").format((Double) value));
                else setText(value == null ? "-" : value.toString());
            }
            { setHorizontalAlignment(SwingConstants.RIGHT); }
        };

// “Net” column uses your existing intRenderer (k/m)
        snapshotTable.getColumnModel().getColumn(1).setCellRenderer(intRenderer);
        snapshotTable.getColumnModel().getColumn(2).setCellRenderer(pctRenderer2);

// Special renderer for column 3: show grand total ONLY at the top visible row
        DefaultTableCellRenderer totalRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(tbl, v, sel, focus, row, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                if (row == 0 && snapshotGrandTotal != null) {
                    setText(fmtKM(snapshotGrandTotal));
                } else {
                    setText("-");
                }
                return this;
            }
        };
        snapshotTable.getColumnModel().getColumn(3).setCellRenderer(totalRenderer);

        JTableHeader snapHdr = new JTableHeader(snapshotTable.getColumnModel()) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int vCol = columnAtPoint(e.getPoint());
                if (vCol < 0) return null;
                int mCol = snapshotTable.convertColumnIndexToModel(vCol);
                switch (mCol) {
                    case 0: return "Item name";
                    case 1: return "Net Gain or Loss = qty × (current − snapshot)";
                    case 2: return "Percentage Change = (current − snapshot) / snapshot";
                    case 3: return "Total Net = Σ over all rows of [qty × (current − snapshot)] "
                            + "(only one cell is populated)";
                    default: return null;
                }
            }
        };
        snapshotTable.setTableHeader(snapHdr);

// sorter
        snapSorter = (TableRowSorter<DefaultTableModel>) snapshotTable.getRowSorter();
        if (snapSorter != null) {
            snapSorter.setComparator(1, Comparator.nullsLast(Integer::compareTo)); // Net
            snapSorter.setComparator(2, Comparator.nullsLast(Double::compare));    // %
            snapSorter.setSortable(3, false);                                      // Total Net column not sortable
        }
        updateFilters();

// Wrap in a titled panel
        JScrollPane snapScroll = new JScrollPane(snapshotTable);
        snapScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel snapPanel = new JPanel(new BorderLayout());
        snapPanel.setBorder(BorderFactory.createTitledBorder("Gain / Loss"));
        snapPanel.add(snapScroll, BorderLayout.CENTER);

        final int TABLE_VIEWPORT_H = 160; // pick what feels good (140–200 works well)


        // Add to SOUTH with a titled border and a sensible height
        JScrollPane detailScroll = new JScrollPane(detailTable);
        detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        detailScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Bottom table panel (unchanged)
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(BorderFactory.createTitledBorder("Price Data"));
        topPanel.add(detailScroll, BorderLayout.CENTER);


// for the Price Distances table
        detailScroll.setPreferredSize(new Dimension(0, TABLE_VIEWPORT_H));

// for the My Items (snapshot) table
        snapScroll.setPreferredSize(new Dimension(0, TABLE_VIEWPORT_H));



// Scrollable vertical stack so each table can keep a meaningful height
        JPanel tablesStack = new JPanel();
        tablesStack.setLayout(new BoxLayout(tablesStack, BoxLayout.Y_AXIS));
        tablesStack.setPreferredSize(new Dimension(0, 3 * (TABLE_VIEWPORT_H + 32))); // + borders

// make panels use their preferred height and full width
        topPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        snapPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

// add the three titled panels with small gaps
        tablesStack.add(Box.createVerticalStrut(6));
        tablesStack.add(topPanel);

        // Add "Price Data Popup" button directly under the first table
        JPanel dataPopupRow = new JPanel();
        dataPopupRow.setLayout(new BoxLayout(dataPopupRow, BoxLayout.X_AXIS));
        dataPopupRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        dataPopupRow.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 0)); // no side padding
        dataPopupRow.add(distancesPopupBtn);

        tablesStack.add(dataPopupRow);
        tablesStack.add(Box.createVerticalStrut(6));
        tablesStack.add(snapPanel);


// ── Net summary chip (under the Gain / Loss table) ──
        netSummaryBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        netSummaryBox.setOpaque(true);
        netSummaryLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        netSummaryLabel.setFont(netSummaryLabel.getFont().deriveFont(netSummaryLabel.getFont().getSize2D() + 0.5f));
        netSummaryLabel.setForeground(Color.WHITE);

// minimal 1px border; background set dynamically
        netSummaryBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70), 1),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
        ));
        netSummaryBox.add(netSummaryLabel, BorderLayout.WEST);
        netSummaryBox.setVisible(false); // hidden until Compare

        tablesStack.add(Box.createVerticalStrut(4)); // small gap under the table
        tablesStack.add(netSummaryBox);

// --- Compare Popup button directly under the Gain / Loss table ---
        JPanel compareRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        compareRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        //comparePopupBtn.setMargin(new Insets(2, 8, 2, 8));
        comparePopupBtn.setMargin(tight);
        compareRow.add(comparePopupBtn);

        tablesStack.add(Box.createVerticalStrut(6));   // same gap as other table→button rows
        tablesStack.add(compareRow);
        // --- Import Named Snapshot button (sits under Compare, above Snapshot row) ---
        JPanel importNamedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        importNamedRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        importNamedSnapBtn.setMargin(tight);
        importNamedRow.add(importNamedSnapBtn);

        tablesStack.add(Box.createVerticalStrut(6));   // same vertical gap as elsewhere
        tablesStack.add(importNamedRow);

// keep your existing gap before the controls block


        tablesStack.add(Box.createVerticalStrut(6)); // then your controls/buttons continue


        /* ── Controls: new buttons row (Snapshot/Import/My Items) on top,
        search in the middle (full width), old buttons (Distances/GainLoss) at bottom ── */
        JPanel controls = new JPanel(new BorderLayout());

        controls.setAlignmentX(Component.LEFT_ALIGNMENT); // <-- add this line
        //tablesStack.add(Box.createVerticalStrut(8));
        tablesStack.add(controls);

// wrap the stack in a scroll pane (one vertical scrollbar for all tables)
        JScrollPane centerScroll = new JScrollPane(
                tablesStack,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        centerScroll.setBorder(null);
        centerScroll.getVerticalScrollBar().setUnitIncrement(16);

        add(centerScroll, BorderLayout.CENTER);

// Controls container (two rows, left aligned)
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);

// Shared button insets for compact look
        final Insets tight = new Insets(2, 8, 2, 8);

// Row 1: Snap / Import / Compare — left aligned
        JPanel snapBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        snapBtnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        snapBtnRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        snapshotBtn.setMargin(tight);
        importBtn.setMargin(tight);
        snapBtnRow.add(snapshotBtn);
        snapBtnRow.add(importBtn);


        controls.add(snapBtnRow);

        JPanel saveRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        saveRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        saveNamedSnapBtn.setMargin(tight);
        saveRow.add(saveNamedSnapBtn);

        controls.add(saveRow);

// Add controls under the two tables
        tablesStack.add(controls);

// (leave your existing listeners for distancesPopupBtn/gainLossPopupBtn as-is)
// Wire empty handlers for the new buttons for now — just to verify UI placement
        snapshotBtn.addActionListener(e -> {
            // Build the full path we’re about to overwrite for a clear warning
            String defaultPath = java.nio.file.Paths
                    .get(System.getProperty("user.home"), ".bank-prices", "snapshot.csv")
                    .toString();

            int choice = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Are you sure you wish to overwrite your default snapshot?\n\n" +
                            "This will replace:\n" + defaultPath,
                    "Overwrite default snapshot?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );

            if (choice == JOptionPane.YES_OPTION) {
                writeSnapshotToDisk();
            } else {
                setStatus("Snapshot canceled.");
            }
        });
        importBtn.addActionListener(e -> {
            // also update the colored Net box after the import finishes
            revealNetBoxOnNextCompute = true;
            importSnapshotAndCompute();
        });
        comparePopupBtn.addActionListener(e -> {
            // Just open the popup with whatever is already in the snapshot model.
            if (snapshotModel.getRowCount() == 0) {
                setStatus("No comparison data. Click Import Default or Import Named Snapshot first.");
                return;
            }

            // Keep the net chip accurate if it’s already visible, but don’t recalc.
            if (netSummaryBox.isVisible()) {
                refreshNetSummaryBox();
            }

            openMyItemsWindow();
        });

        // Wire popup actions
        distancesPopupBtn.addActionListener(e -> openPriceDistancesWindow());
        gainLossPopupBtn.addActionListener(e -> openGainLossWindow()); // ◀ new handler


        // --- Double-click row to open details popup (volatility & distances) ---
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    if (modelRow < 0 || modelRow >= backingRows.size()) return;
                    BankStatsPlugin.Row r = backingRows.get(modelRow);
                    showDetailPopup(r);
                }
            }
        });


        updateBtn.setFont(updateBtn.getFont().deriveFont(14f));
        status.setForeground(new Color(200, 200, 200));

        updateBtn.addActionListener(e -> {
            updateBtn.setEnabled(false);
            status.setText("Preparing...");
            onUpdate.run();
        });
        exportBtn.addActionListener(e -> exportToCsv());

        saveNamedSnapBtn.addActionListener(e -> saveNamedSnapshot());
        importNamedSnapBtn.addActionListener(e -> {
            revealNetBoxOnNextCompute = true;            // auto-show/update the Net chip
            importNamedSnapshotAndComputeViaChooser();   // pick a file and fill the table
        });
    }

    public void setUpdating(boolean updating)
    {
        SwingUtilities.invokeLater(() -> updateBtn.setEnabled(!updating));
    }

    public void setStatus(String text)
    {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    public void clearTable()
    {
        SwingUtilities.invokeLater(() -> model.setRowCount(0));
    }

    public void setTableData(List<BankStatsPlugin.Row> rows)
    {
        SwingUtilities.invokeLater(() -> {
            backingRows = new ArrayList<>(rows); // store for popup
            model.setRowCount(0);
            for (BankStatsPlugin.Row r : rows)
            {
                model.addRow(new Object[]{
                        r.name,        // String
                        r.currentHigh, // Integer
                        r.weekLow,     // Integer
                        r.weekHigh,    // Integer
                        r.gainLoss     // Integer
                });
            }
        });
    }
    public void setDetailTableData(List<BankStatsPlugin.Row> rows)
    {
        SwingUtilities.invokeLater(() -> {
            detailModel.setRowCount(0);
            for (BankStatsPlugin.Row r : rows)
            {
                detailModel.addRow(new Object[]{
                        r.name,               // 0: Item
                        r.currentHigh,        // 1: AvgHighPrice (Integer, rendered k/m)
                        r.distTo7LowPct,      // 2: 7d Low (%)
                        r.distTo7HighPct,     // 3: 7d High (%)
                        r.distTo30LowPct,     // 4: 30d Low (%)
                        r.distTo30HighPct,    // 5: 30d High (%)
                        r.distTo6moLowPct,    // 6: 6mo Low (%)   ◀ add
                        r.distTo6moHighPct    // 7: 6mo High (%)  ◀ add
                });
            }
        });
    }

    private void openMyItemsWindow()
    {
        // If already open, just refresh and focus
        if (myItemsDlg != null && myItemsDlg.isShowing()) {
            importSnapshotAndCompute();  // ← refresh while the dialog is open
            myItemsDlg.toFront();
            myItemsDlg.requestFocus();
            return;
        }
        // Singleton behavior
        if (myItemsDlg != null && myItemsDlg.isShowing()) {
            myItemsDlg.toFront();
            myItemsDlg.requestFocus();
            return;
        }

        final Color STRIPE_1 = new Color(218, 232, 252);
        final Color STRIPE_2 = new Color(255, 242, 204);

        JTable popupTable = new JTable(snapshotModel) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column)
            {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    c.setBackground((row % 2 == 0) ? STRIPE_1 : STRIPE_2);
                    c.setForeground(Color.BLACK);
                } else {
                    c.setBackground(getSelectionBackground());
                    c.setForeground(getSelectionForeground());
                }
                return c;
            }
            @Override
            public String getToolTipText(MouseEvent e) {
                java.awt.Point p = e.getPoint();
                int viewRow = rowAtPoint(p);
                int viewCol = columnAtPoint(p);
                if (viewRow < 0 || viewCol < 0) return null;

                int modelRow = convertRowIndexToModel(viewRow);
                Object nameObj = snapshotModel.getValueAt(modelRow, 0);
                String itemName = (nameObj == null) ? "" : nameObj.toString();

                Object value = getValueAt(viewRow, viewCol);
                String valStr;
                if (value == null) {
                    valStr = "-";
                } else if (value instanceof Integer) {
                    valStr = NumberFormat.getIntegerInstance(Locale.US)
                            .format(((Integer) value).intValue());
                } else if (value instanceof Double) {
                    valStr = new java.text.DecimalFormat("0.0%")
                            .format(((Double) value).doubleValue());
                } else {
                    valStr = value.toString();
                }
                return itemName + " = " + valStr;
            }
        };

        popupTable.setToolTipText("");   // <- enable tooltips for the popup table
        popupTable.setAutoCreateRowSorter(true);
        popupTable.setRowHeight(22);
        popupTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // widths
        popupTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        popupTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        popupTable.getColumnModel().getColumn(2).setPreferredWidth(140);
        popupTable.getColumnModel().getColumn(3).setPreferredWidth(200);

// renderers: reuse k/m for ints and a % renderer
        DefaultTableCellRenderer km = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object v) { setText(v instanceof Integer ? fmtKM((Integer) v) : (v == null ? "-" : v.toString())); }
            { setHorizontalAlignment(SwingConstants.RIGHT); }
        };
        DefaultTableCellRenderer pctR = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object v) {
                if (v instanceof Double) setText(new java.text.DecimalFormat("0.0%").format((Double) v));
                else setText(v == null ? "-" : v.toString());
            }
            { setHorizontalAlignment(SwingConstants.RIGHT); }
        };
        popupTable.getColumnModel().getColumn(1).setCellRenderer(km);   // Net
        popupTable.getColumnModel().getColumn(2).setCellRenderer(pctR); // %
        DefaultTableCellRenderer totalR = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(tbl, v, sel, focus, row, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                if (row == 0 && snapshotGrandTotal != null) setText(fmtKM(snapshotGrandTotal));
                else setText("-");
                return this;
            }
        };
        popupTable.getColumnModel().getColumn(3).setCellRenderer(totalR);



        JTableHeader popHdr = new JTableHeader(popupTable.getColumnModel()) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int vCol = columnAtPoint(e.getPoint());
                if (vCol < 0) return null;
                int mCol = popupTable.convertColumnIndexToModel(vCol);
                switch (mCol) {
                    case 0: return "Item name";
                    case 1: return "Net Gain or Loss = qty × (current − snapshot)";
                    case 2: return "Percentage Change = (current − snapshot) / snapshot";
                    case 3: return "Total Net = Σ qty × (current − snapshot) (only one cell is populated)";
                    default: return null;
                }
            }
        };
        popupTable.setTableHeader(popHdr);


// Make the Compare popup respect the current search (like Price Distances)
        TableRowSorter<DefaultTableModel> popupSorter =
                (TableRowSorter<DefaultTableModel>) popupTable.getRowSorter();
        if (popupSorter != null) {
            // comparators for Net (Integer) and % (Double); don't sort the Total column
            popupSorter.setComparator(1, Comparator.nullsLast(Integer::compareTo));
            popupSorter.setComparator(2, Comparator.nullsLast(Double::compare));
            popupSorter.setSortable(3, false);

            // copy the current filter from the embedded snapshot table's sorter
            if (snapSorter != null) {
                popupSorter.setRowFilter(snapSorter.getRowFilter());
            }
        }

// sorting: enable for cols 1–2, disable for col 3
        TableRowSorter<DefaultTableModel> srt = (TableRowSorter<DefaultTableModel>) popupTable.getRowSorter();
        if (srt != null) {
            srt.setComparator(1, Comparator.nullsLast(Integer::compareTo));
            srt.setComparator(2, Comparator.nullsLast(Double::compare));
            srt.setSortable(3, false);
        }
        popupTable.getColumnModel().getColumn(1).setCellRenderer(km);

        myItemsDlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Gain / Loss", Dialog.ModalityType.MODELESS);
        myItemsDlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        myItemsDlg.setContentPane(new JScrollPane(
                popupTable,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ));
        myItemsDlg.setSize(1100, 620);
        myItemsDlg.setLocationRelativeTo(this);
        myItemsDlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { myItemsDlg = null; }
            @Override public void windowClosing(java.awt.event.WindowEvent e) { myItemsDlg = null; }
        });
        myItemsDlg.setVisible(true);
    }


    private void openPriceDistancesWindow()
    {
        // Share the same model so updates reflect immediately
        final DefaultTableModel tm = this.detailModel;

        final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        final java.text.DecimalFormat pctFmt = new java.text.DecimalFormat("0.0%");

        final Color STRIPE_1 = new Color(218, 232, 252);
        final Color STRIPE_2 = new Color(255, 242, 204);

        JTable popupTable = new JTable(tm) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column)
            {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    c.setBackground((row % 2 == 0) ? STRIPE_1: STRIPE_2);
                    c.setForeground(Color.BLACK);                    // ← make text black for normal rows
                } else {
                    c.setBackground(getSelectionBackground());
                    c.setForeground(getSelectionForeground());       // ← keep theme color when selected
                }
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent e)
            {
                java.awt.Point p = e.getPoint();
                int viewRow = rowAtPoint(p);
                int viewCol = columnAtPoint(p);
                if (viewRow < 0 || viewCol < 0) return null;

                int modelRow = convertRowIndexToModel(viewRow);
                Object nameObj = tm.getValueAt(modelRow, 0);
                String itemName = (nameObj == null) ? "" : nameObj.toString();

                Object value = getValueAt(viewRow, viewCol);
                String valStr;
                if (value == null) {
                    valStr = "-";
                } else if (value instanceof Integer) {
                    valStr = nf.format(((Integer) value).intValue());
                } else if (value instanceof Double) {
                    valStr = pctFmt.format(((Double) value).doubleValue());
                } else {
                    valStr = value.toString();
                }
                return itemName + " = " + valStr;
            }
        };
        popupTable.setToolTipText("");
        popupTable.setAutoCreateRowSorter(true);
        popupTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Renderers: k/m for price col (1), % for distance cols (2..end)
        DefaultTableCellRenderer intRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) { setText(fmtKM((Integer) value)); }
            { setHorizontalAlignment(SwingConstants.RIGHT); }
        };
        DefaultTableCellRenderer pctRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (value instanceof Double) setText(pctFmt.format(((Double) value).doubleValue()));
                else setText(value == null ? "-" : value.toString());
            }
            { setHorizontalAlignment(SwingConstants.RIGHT); }
        };

        int colCount = tm.getColumnCount();
        if (colCount > 1) popupTable.getColumnModel().getColumn(1).setCellRenderer(intRenderer);
        for (int c = 2; c < colCount; c++) {
            popupTable.getColumnModel().getColumn(c).setCellRenderer(pctRenderer);
        }

        // Starting widths (will clamp to available columns)
        int[] widths = {220, 140, 150, 150, 170, 170, 180, 180};
        for (int i = 0; i < Math.min(widths.length, colCount); i++) {
            popupTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        detailSorter = (TableRowSorter<DefaultTableModel>) detailTable.getRowSorter();
        // Sorter comparators + copy current filter (so search applies at open time)
        TableRowSorter<DefaultTableModel> popupSorter =
                (TableRowSorter<DefaultTableModel>) popupTable.getRowSorter();
        if (popupSorter != null) {
            popupSorter.setComparator(1, Comparator.nullsLast(Integer::compareTo));
            for (int c = 2; c < colCount; c++) {
                popupSorter.setComparator(c, Comparator.nullsLast(Double::compare));
            }
            // Respect current filter if present
            if (detailSorter != null) {
                popupSorter.setRowFilter(detailSorter.getRowFilter());
            }
        }

        // Header tooltips
        JTableHeader hdr = new JTableHeader(popupTable.getColumnModel()) {
            @Override
            public String getToolTipText(MouseEvent e)
            {
                int vCol = columnAtPoint(e.getPoint());
                if (vCol < 0) return null;
                int mCol = popupTable.convertColumnIndexToModel(vCol);
                switch (mCol) {
                    case 0: return "Item name";
                    case 1: return "Current price (high) from /latest";
                    case 2: return "Dist. to 7d Low = (current − minMid7) / minMid7";
                    case 3: return "Dist. to 7d High = (maxMid7 − current) / maxMid7";
                    case 4: return "Dist. to 30d Low = (current − minMid30) / minMid30";
                    case 5: return "Dist. to 30d High = (maxMid30 − current) / maxMid30";
                    case 6: return "Dist. to 6mo Low = (current − minMid180) / minMid180";
                    case 7: return "Dist. to 6mo High = (maxMid180 − current) / maxMid180";
                    default: return null;
                }
            }
        };
        popupTable.setTableHeader(hdr);

        // Singleton: if already open, just focus it
        if (distancesDlg != null && distancesDlg.isShowing()) {
            distancesDlg.toFront();
            distancesDlg.requestFocus();
            return;
        }

        distancesDlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Price Distances", Dialog.ModalityType.MODELESS);
        distancesDlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        distancesDlg.setContentPane(new JScrollPane(
                popupTable,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ));
        distancesDlg.setSize(1100, 620);
        distancesDlg.setLocationRelativeTo(this);

        // When the window closes, clear the ref so it can be reopened
        distancesDlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { distancesDlg = null; }
            @Override public void windowClosing(java.awt.event.WindowEvent e) { distancesDlg = null; }
        });
        distancesDlg.setVisible(true);
    }

    private void openGainLossWindow()
    {
        // Share the same model as the main (Gain/Loss) table so updates reflect immediately
        final DefaultTableModel tm = this.model;

        final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        // Pleasant, low-contrast stripes (bluish-grays)
        final Color STRIPE_1 = new Color(218, 232, 252);
        final Color STRIPE_2 = new Color(255, 242, 204);

        JTable popupTable = new JTable(tm) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column)
            {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    c.setBackground((row % 2 == 0) ? STRIPE_1: STRIPE_2);
                    c.setForeground(Color.BLACK);                    // ← make text black for normal rows
                } else {
                    c.setBackground(getSelectionBackground());
                    c.setForeground(getSelectionForeground());       // ← keep theme color when selected
                }
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent e)
            {
                java.awt.Point p = e.getPoint();
                int viewRow = rowAtPoint(p);
                int viewCol = columnAtPoint(p);
                if (viewRow < 0 || viewCol < 0) return null;

                int modelRow = convertRowIndexToModel(viewRow);
                Object nameObj = tm.getValueAt(modelRow, 0);
                String itemName = (nameObj == null) ? "" : nameObj.toString();

                Object value = getValueAt(viewRow, viewCol);
                String valStr;
                if (value == null) {
                    valStr = "-";
                } else if (value instanceof Integer) {
                    valStr = nf.format(((Integer) value).intValue());
                } else if (value instanceof Double) {
                    valStr = new java.text.DecimalFormat("0.0%").format(((Double) value).doubleValue());
                } else {
                    valStr = value.toString();
                }
                return itemName + " = " + valStr;
            }
        };
        popupTable.setToolTipText("");
        popupTable.setAutoCreateRowSorter(true);
        popupTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Renderers: k/m abbreviation for all numeric columns (1..4)
        DefaultTableCellRenderer intRenderer = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) { setText(fmtKM((Integer) value)); }
            { setHorizontalAlignment(SwingConstants.RIGHT); }
        };
        // Apply renderer safely to the expected columns
        int colCount = tm.getColumnCount(); // should be 5: Item + 4 ints
        for (int c = 1; c < Math.min(colCount, 5); c++) {
            popupTable.getColumnModel().getColumn(c).setCellRenderer(intRenderer);
        }

        // Nice starting widths
        int[] widths = {240, 140, 130, 130, 180}; // Item, Current, 7d Low, 7d High, Gain−Loss
        for (int i = 0; i < Math.min(widths.length, colCount); i++) {
            popupTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        // Sorter comparators + copy current filter (respect search at open time)
        TableRowSorter<DefaultTableModel> popupSorter =
                (TableRowSorter<DefaultTableModel>) popupTable.getRowSorter();
        if (popupSorter != null) {
            // null-safe Integer comparators for columns 1..4
            Comparator<Integer> nullSafe = Comparator.nullsLast(Integer::compareTo);
            for (int c = 1; c < Math.min(colCount, 5); c++) {
                popupSorter.setComparator(c, nullSafe);
            }
            // Respect current main table filter (search)
            if (mainSorter != null) {
                popupSorter.setRowFilter(mainSorter.getRowFilter());
            }
        }

        // Header tooltips
        JTableHeader hdr = new JTableHeader(popupTable.getColumnModel()) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int vCol = columnAtPoint(e.getPoint());
                if (vCol < 0) return null;
                int mCol = popupTable.convertColumnIndexToModel(vCol);
                switch (mCol) {
                    case 0: return "Item name";
                    case 1: return "Net Gain or Loss = qty × (current − snapshot)";
                    case 2: return "Percentage Change = (current − snapshot) / snapshot";
                    case 3: return "Total Net = Σ qty × (current − snapshot) "
                            + "(only one cell is populated)";
                    default: return null;
                }
            }
        };
        popupTable.setTableHeader(hdr);

        // Singleton: if already open, just focus it
        if (gainLossDlg != null && gainLossDlg.isShowing()) {
            gainLossDlg.toFront();
            gainLossDlg.requestFocus();
            return;
        }

        gainLossDlg = new JDialog(SwingUtilities.getWindowAncestor(this), "GainLoss", Dialog.ModalityType.MODELESS);
        gainLossDlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        gainLossDlg.setContentPane(new JScrollPane(
                popupTable,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ));
        gainLossDlg.setSize(1100, 620); // same “large” size as the distances window for consistency
        gainLossDlg.setLocationRelativeTo(this);
        gainLossDlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { gainLossDlg = null; }
            @Override public void windowClosing(java.awt.event.WindowEvent e) { gainLossDlg = null; }
        });
        gainLossDlg.setVisible(true);
    }

    private void showDetailPopup(BankStatsPlugin.Row r)
    {
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Item details", Dialog.ModalityType.MODELESS);
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel(r.name);
        title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 1f));
        root.add(title, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 6));

        DecimalFormat pct = new DecimalFormat("0.0%");
        DecimalFormat volFmt = new DecimalFormat("0.00"); // log-return std dev (daily)

        // Current & 7d range (already in table, but useful context)
        grid.add(new JLabel("Current (high):"));
        grid.add(new JLabel(r.currentHigh == null ? "-" : fmtKM(r.currentHigh)));

        grid.add(new JLabel("7d Low:"));
        grid.add(new JLabel(r.weekLow == null ? "-" : fmtKM(r.weekLow)));

        grid.add(new JLabel("7d High:"));
        grid.add(new JLabel(r.weekHigh == null ? "-" : fmtKM(r.weekHigh)));

        // Distances to 30d extremes (as percentages)
        grid.add(new JLabel("Dist. to 30d Low:"));
        grid.add(new JLabel(r.distTo30LowPct == null ? "-" : pct.format(r.distTo30LowPct)));

        grid.add(new JLabel("Dist. to 30d High:"));
        grid.add(new JLabel(r.distTo30HighPct == null ? "-" : pct.format(r.distTo30HighPct)));

        grid.add(new JLabel("Dist. to 6mo Low:"));
        grid.add(new JLabel(r.distTo6moLowPct == null ? "-" : pct.format(r.distTo6moLowPct)));

        grid.add(new JLabel("Dist. to 6mo High:"));
        grid.add(new JLabel(r.distTo6moHighPct == null ? "-" : pct.format(r.distTo6moHighPct)));


        // Volatility
        grid.add(new JLabel("Volatility (7d):"));
        grid.add(new JLabel(r.vol7 == null ? "-" : volFmt.format(r.vol7)));

        grid.add(new JLabel("Volatility (30d):"));
        grid.add(new JLabel(r.vol30 == null ? "-" : volFmt.format(r.vol30)));

        // Distances to 7d extremes (as percentages)
        grid.add(new JLabel("Dist. to 7d Low:"));
        grid.add(new JLabel(r.distTo7LowPct == null ? "-" : pct.format(r.distTo7LowPct)));

        grid.add(new JLabel("Dist. to 7d High:"));
        grid.add(new JLabel(r.distTo7HighPct == null ? "-" : pct.format(r.distTo7HighPct)));

        root.add(grid, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(ev -> dlg.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);
        root.add(south, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }


}
