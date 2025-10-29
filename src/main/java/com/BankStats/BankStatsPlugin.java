package com.BankStats;


// add with the other java.util.concurrent imports
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

// add for OkHttp dispatcher tuning & timeouts
import okhttp3.Dispatcher;
import java.util.concurrent.TimeUnit;

import com.google.gson.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import net.runelite.client.util.ImageUtil;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

@PluginDescriptor(
        name = "Bank Prices Panel",
        description = "Shows bank item names with current and weekly high/low prices. Updates only when you click while bank is open.",
        tags = {"bank", "prices", "panel", "wiki"}
)
public class BankStatsPlugin extends Plugin
{
    // ===== Injected services =====
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ItemManager itemManager;

    private OkHttpClient okHttpClient;


    // ===== UI =====
    private NavigationButton navButton;
    private BankStatsPanel panel;

    // ===== Net / JSON =====
    private final Gson gson = new GsonBuilder().create();

    // If you want to limit parallelism, use an executor.
    private static final int CONCURRENCY = 24; // good balance for speed vs. politeness
    private final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);


    // ===== Constants =====
    private static final String UA = "BankPricesPanel/1.0 (contact: set-your-email@example.com)";
    private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest?id=";
    private static final String TIMESERIES_URL = "https://prices.runescape.wiki/api/v1/osrs/timeseries?timestep=24h&id=";


    // Allow the panel to fetch latest highs for a set of ids asynchronously.
    public void fetchLatestForIdsAsync(java.util.Set<Integer> ids,
                                       java.util.function.Consumer<java.util.Map<Integer, Integer>> onDone)
    {
        executor.submit(() -> {
            Map<Integer, Integer> latest;
            try {
                latest = fetchLatestBulk(ids);
            } catch (IOException e) {
                latest = java.util.Collections.emptyMap();
            }
            final Map<Integer, Integer> result = latest;
            SwingUtilities.invokeLater(() -> onDone.accept(result));
        });
    }

    @Override
    protected void startUp()
    {
        // --- build a tuned OkHttp client (timeouts + higher concurrency) ---
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(32);
        dispatcher.setMaxRequestsPerHost(16);

        okHttpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
// -------------------------------------------------------------------

        panel = new BankStatsPanel(this, () -> requestUpdate());

        BufferedImage icon = ImageUtil.loadImageResource(BankStatsPlugin.class, "bankprices.png");

        navButton = NavigationButton.builder()
                .tooltip("Bank Prices")
                .icon(icon)          // <-- important: set an icon so it shows in the toolbar
                .priority(5)
                .panel(panel)
                .build();

        // Add on the Swing EDT
        SwingUtilities.invokeLater(() -> clientToolbar.addNavigation(navButton));

        // Optional: prove startUp ran
        clientThread.invokeLater(() ->
                client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Bank Prices: panel added", null)
        );
    }


    @Override
    protected void shutDown()
    {
        if (navButton != null)
        {
            SwingUtilities.invokeLater(() -> clientToolbar.removeNavigation(navButton));
            navButton = null;
        }
        panel = null;
    }


    // Called by the panel's button (on Swing EDT). We hop appropriately between threads.
    private void requestUpdate()
    {
        // Disable button immediately on the EDT
        panel.setUpdating(true);
        panel.clearTable();

        // Step 1: On the client thread, verify bank is open and snapshot IDs/names
        clientThread.invoke(() -> {
            if (!isBankOpen())
            {
                panel.setStatus("Open your bank, then click Update.");
                panel.setUpdating(false);
                return;
            }

            ItemContainer bank = client.getItemContainer(InventoryID.BANK);
            if (bank == null || bank.getItems() == null)
            {
                panel.setStatus("Bank not found. Open your bank first.");
                panel.setUpdating(false);
                return;
            }

            // Snapshot canonical item IDs and quantities
            Set<Integer> ids = new LinkedHashSet<>();
            Map<Integer, Integer> qtyMap = new HashMap<>();

            for (Item it : bank.getItems())
            {
                if (it == null) continue;
                int id = it.getId();
                int qty = it.getQuantity();
                if (id <= 0 || qty <= 0) continue;

                int canon = itemManager.canonicalize(id);
                if (canon > 0)
                {
                    ids.add(canon);
                    qtyMap.merge(canon, qty, Integer::sum);
                }
            }


            // ids currently holds every canonicalized bank item id


            // Snapshot names now (safe to call ItemManager here)
            // Build names for ALL ids we found in the bank
            Map<Integer, String> names = new HashMap<>();
            for (int id : ids)
            {
                try
                {
                    String nm = itemManager.getItemComposition(id).getName();
                    names.put(id, nm);
                }
                catch (Exception ex)
                {
                    names.put(id, "Item " + id);
                }
            }

// Kick off background fetch for ALL ids
            panel.setStatus("Fetching prices for " + ids.size() + " items...");
            executor.submit(() -> fetchAndDisplay(ids, names, qtyMap));

        });
    }

    private boolean isBankOpen()
    {
        Widget bank = client.getWidget(WidgetInfo.BANK_CONTAINER);
        return bank != null && !bank.isHidden();
    }

    private void fetchAndDisplay(Set<Integer> ids, Map<Integer, String> names, Map<Integer, Integer> qtyMap)
    {
        try
        {
            // 1) Bulk latest (single request)
            panel.setStatus("Fetching latest (bulk)...");
            Map<Integer, Integer> latest = fetchLatestBulk(ids);

            // 2) Parallel timeseries
            panel.setStatus("Fetching timeseries for " + ids.size() + " items (parallel)...");
            CompletionService<Row> cs = new ExecutorCompletionService<>(executor);

            int submitted = 0;
            for (int id : ids)
            {
                final int fid = id;
                final int qty = qtyMap.getOrDefault(fid, 0);
                cs.submit(() -> {
                    Integer currentHigh = latest.get(fid); // fast: from bulk map
                    // Insert here
                    Integer weekLow = null;
                    Integer weekHigh = null;

                    Double vol7 = null;
                    Double vol30 = null;
                    Double distLowPct = null;
                    Double distHighPct = null;
                    Double distLow30Pct = null;
                    Double distHigh30Pct = null;
                    Double distLow180Pct = null;
                    Double distHigh180Pct = null;


                    try
                    {
                        // fetch richer stats for this item
                        SeriesStats s = fetchWeekStatsWithRetry(fid);


                        weekLow  = s.minLow;
                        weekHigh = s.maxHigh;

                        // distances to 7d extremes, using midpoints
                        if (currentHigh != null && s.minMid7 != null && s.minMid7 > 0)
                        {
                            distLowPct = (currentHigh - s.minMid7) / (double) s.minMid7;   // e.g., 0.12 = +12%
                        }
                        if (currentHigh != null && s.maxMid7 != null && s.maxMid7 > 0)
                        {
                            distHighPct = (s.maxMid7 - currentHigh) / (double) s.maxMid7;  // e.g., 0.08 = 8%
                        }

                        // distances to 30d extremes, using midpoints
                        if (currentHigh != null && s.minMid30 != null && s.minMid30 > 0)
                        {
                            distLow30Pct = (currentHigh - s.minMid30) / (double) s.minMid30;
                        }
                        if (currentHigh != null && s.maxMid30 != null && s.maxMid30 > 0)
                        {
                            distHigh30Pct = (s.maxMid30 - currentHigh) / (double) s.maxMid30;
                        }


                        vol7  = s.vol7;   // daily log-return std dev over last 7 mids
                        vol30 = s.vol30;  // … over last 30 mids

                        // distances to 6mo extremes (180d), using midpoints
                        if (currentHigh != null && s.minMid180 != null && s.minMid180 > 0)
                        {
                            distLow180Pct = (currentHigh - s.minMid180) / (double) s.minMid180;
                        }
                        if (currentHigh != null && s.maxMid180 != null && s.maxMid180 > 0)
                        {
                            distHigh180Pct = (s.maxMid180 - currentHigh) / (double) s.maxMid180;
                        }
                    }
                    catch (IOException ignored)
                    {
                        // leave nulls if this item’s timeseries fails
                    }

                    // ── Skip untradable / no-price items ─────────────────────────
                    // If nothing priced at all (no latest high, no 7d low/high),
                    // we treat it as untradable and drop it from all tables.
                    if (currentHigh == null && weekLow == null && weekHigh == null)
                    {
                        return null;
                    }
                    // ─────────────────────────────────────────────────────────────

                    // construct Row with the new fields
                    return new Row(
                            fid,
                            names.getOrDefault(fid, "Item " + fid),
                            qty,
                            currentHigh,
                            weekLow,
                            weekHigh,
                            vol7,
                            vol30,
                            distLowPct,
                            distHighPct,
                            distLow30Pct,
                            distHigh30Pct,
                            distLow180Pct,     // ◀ add
                            distHigh180Pct
                    );


                });
                submitted++;
            }

            // 3) Collect as they finish; update status every 20 items
            List<Row> rows = new ArrayList<>(ids.size());
            for (int i = 0; i < submitted; i++)
            {
                try
                {
                    Future<Row> f = cs.take();
                    Row r = f.get();
                    if (r != null) rows.add(r);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
                catch (Exception ignored)
                {
                }

                if ((i + 1) % 20 == 0 || (i + 1) == submitted)
                {
                    panel.setStatus("Fetched " + (i + 1) + " / " + submitted);
                }
            }

            // 4) Publish to UI
            panel.setTableData(rows);
            panel.setDetailTableData(rows); // <-- add this line
            panel.setStatus("Done. " + rows.size() + " items.");
        }
        catch (IOException bulkErr)
        {
            panel.setStatus("Latest (bulk) failed: " + bulkErr.getMessage());
        }
        finally
        {
            panel.setUpdating(false);
        }
    }


    // ===== Networking helpers =====

    private Integer fetchLatestHigh(int id) throws IOException
    {
        Request req = new Request.Builder()
                .url(LATEST_URL + id)
                .header("User-Agent", UA)
                .build();

        try (Response resp = okHttpClient.newCall(req).execute())
        {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JsonObject root = gson.fromJson(resp.body().charStream(), JsonObject.class);
            JsonObject data = root.getAsJsonObject("data");
            if (data == null) return null;
            JsonObject perId = data.getAsJsonObject(Integer.toString(id));
            if (perId == null) return null;
            JsonElement high = perId.get("high");
            return high != null && !high.isJsonNull() ? high.getAsInt() : null;
        }
    }

    private SeriesStats fetchWeekStats(int id) throws IOException
    {
        Request req = new Request.Builder()
                .url(TIMESERIES_URL + id)
                .header("User-Agent", UA)
                .build();

        try (Response resp = okHttpClient.newCall(req).execute())
        {
            if (!resp.isSuccessful() || resp.body() == null)
            {
                return new SeriesStats(null, null,
                        Collections.emptyList(), Collections.emptyList(),
                        null, null, null, null, null, null,null,null);
            }
            JsonObject root = gson.fromJson(resp.body().charStream(), JsonObject.class);
            JsonArray arr = root.getAsJsonArray("data");
            if (arr == null || arr.size() == 0)
            {
                return new SeriesStats(null, null,
                        Collections.emptyList(), Collections.emptyList(),
                        null, null, null, null, null, null,null,null);
            }

            // Build midpoints for whole series, newest last
            List<Integer> midsAll = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++)
            {
                JsonObject o = arr.get(i).getAsJsonObject();
                Integer low = getIntOrNull(o, "avgLowPrice");
                Integer high = getIntOrNull(o, "avgHighPrice");
                Integer mid = midpoint(low, high);
                if (mid != null && mid > 0) midsAll.add(mid);
            }

            // Last 7 and 30 midpoints (newest last)
            List<Integer> mids7 = midsAll.size() <= 7 ? new ArrayList<>(midsAll)
                    : new ArrayList<>(midsAll.subList(midsAll.size() - 7, midsAll.size()));
            List<Integer> mids30 = midsAll.size() <= 30 ? new ArrayList<>(midsAll)
                    : new ArrayList<>(midsAll.subList(midsAll.size() - 30, midsAll.size()));
            // 30d mid extrema
            Integer minMid30 = null, maxMid30 = null;
            for (Integer m : mids30)
            {
                if (m == null || m <= 0) continue;
                minMid30 = (minMid30 == null) ? m : Math.min(minMid30, m);
                maxMid30 = (maxMid30 == null) ? m : Math.max(maxMid30, m);
            }

            // ▼▼ ADD THIS: 180d mid extrema (≈6 months) ▼▼
            List<Integer> mids180 = midsAll.size() <= 180 ? new ArrayList<>(midsAll)
                    : new ArrayList<>(midsAll.subList(midsAll.size() - 180, midsAll.size()));
            Integer minMid180 = null, maxMid180 = null;
            for (Integer m : mids180)
            {
                if (m == null || m <= 0) continue;
                minMid180 = (minMid180 == null) ? m : Math.min(minMid180, m);
                maxMid180 = (maxMid180 == null) ? m : Math.max(maxMid180, m);
            }

            // 7d low/high based on original low/high fields (as you had)
            int start7 = Math.max(0, arr.size() - 7);
            Integer minLow = null, maxHigh = null;
            Integer minMid7 = null, maxMid7 = null;

            for (int i = start7; i < arr.size(); i++)
            {
                JsonObject o = arr.get(i).getAsJsonObject();

                Integer low = getIntOrNull(o, "avgLowPrice");
                Integer high = getIntOrNull(o, "avgHighPrice");
                if (low != null && low > 0)
                    minLow = (minLow == null) ? low : Math.min(minLow, low);
                if (high != null && high > 0)
                    maxHigh = (maxHigh == null) ? high : Math.max(maxHigh, high);

                Integer mid = midpoint(low, high);
                if (mid != null && mid > 0)
                {
                    minMid7 = (minMid7 == null) ? mid : Math.min(minMid7, mid);
                    maxMid7 = (maxMid7 == null) ? mid : Math.max(maxMid7, mid);
                }
            }

            // Volatilities
            Double vol7 = volatilityFromMids(mids7);
            Double vol30 = volatilityFromMids(mids30);

            return new SeriesStats(minLow, maxHigh, mids7, mids30, vol7, vol30,
                    minMid7, maxMid7, minMid30, maxMid30,
                    minMid180, maxMid180);

        }
    }


    private Integer getIntOrNull(JsonObject o, String key)
    {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        try { return e.getAsInt(); } catch (Exception ex) { return null; }
    }

    // --- volatility helpers (std dev of daily log returns) ---
    private static Double volatilityFromMids(List<Integer> mids)
    {
        if (mids == null || mids.size() < 2) return null;
        // daily log returns r_t = ln(P_t / P_{t-1})
        double sum = 0.0, sumSq = 0.0;
        int n = 0;
        for (int i = 1; i < mids.size(); i++)
        {
            int prev = mids.get(i - 1);
            int curr = mids.get(i);
            if (prev <= 0 || curr <= 0) continue;
            double r = Math.log((double) curr / (double) prev);
            sum += r;
            sumSq += r * r;
            n++;
        }
        if (n < 2) return null;
        double mean = sum / n;
        double var = Math.max(0.0, (sumSq / n) - (mean * mean));
        return Math.sqrt(var); // daily vol (log-return std dev)
    }

    private static Integer midpoint(Integer low, Integer high)
    {
        if (low == null && high == null) return null;
        if (low == null) return high;
        if (high == null) return low;
        long m = ((long) low + (long) high) / 2L;
        return (int) m;
    }
// ---------------------------------------------------------


    /**
     * Fetch all latest prices in one call, then pick out just the ids we need.
     * Endpoint: https://prices.runescape.wiki/api/v1/osrs/latest  (no ?id=)
     * Returns: id -> current "high" price
     */
    private Map<Integer, Integer> fetchLatestBulk(Set<Integer> ids) throws IOException
    {
        Request req = new Request.Builder()
                .url("https://prices.runescape.wiki/api/v1/osrs/latest")
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build();

        try (Response resp = okHttpClient.newCall(req).execute())
        {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new IOException("bulk latest http " + resp.code());

            JsonObject root = gson.fromJson(resp.body().charStream(), JsonObject.class);
            JsonObject data = root.getAsJsonObject("data");
            Map<Integer, Integer> out = new HashMap<>(ids.size());
            if (data == null) return out;

            for (int id : ids)
            {
                JsonObject perId = data.getAsJsonObject(Integer.toString(id));
                if (perId != null)
                {
                    JsonElement high = perId.get("high");
                    if (high != null && !high.isJsonNull())
                        out.put(id, high.getAsInt());
                }
            }
            return out;
        }
    }

    private static final int MAX_RETRIES_429 = 2;
    private static final long RETRY_SLEEP_MS = 800;

    // Retry wrapper for the new stats method
    private SeriesStats fetchWeekStatsWithRetry(int id) throws IOException
    {
        IOException last = null;
        for (int i = 0; i <= MAX_RETRIES_429; i++)
        {
            try
            {
                return fetchWeekStats(id);
            }
            catch (IOException e)
            {
                last = e;
                String msg = e.getMessage();
                boolean tooMany = msg != null && (msg.contains("429") || msg.contains("Too Many Requests"));
                if (tooMany && i < MAX_RETRIES_429)
                {
                    try { Thread.sleep(RETRY_SLEEP_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                break;
            }
        }
        throw last != null ? last : new IOException("timeseries failed for id " + id);
    }



    static class Row
    {
        final int id;
        final String name;
        final Integer qty;          // NEW: quantity held
        final Integer currentHigh;  // current "high" from /latest
        final Integer weekLow;      // min avgLowPrice over last 7 points
        final Integer weekHigh;     // max avgHighPrice over last 7 points
        final Integer gainLoss;     // qty × [ 2*current − (weekLow + weekHigh) ]

        // Existing metrics for popup
        final Double vol7;          // daily log-return std dev over last 7 points (mid)
        final Double vol30;         // daily log-return std dev over last 30 points (mid)
        final Double distTo7LowPct;   // (current − minMid7) / minMid7
        final Double distTo7HighPct;  // (maxMid7 − current) / maxMid7
        final Double distTo30LowPct;  // (current − minMid30) / minMid30
        final Double distTo30HighPct; // (maxMid30 − current) / maxMid30
        final Double distTo6moLowPct;   // (current − minMid180) / minMid180
        final Double distTo6moHighPct;

        Row(int id, String name, Integer qty, Integer currentHigh, Integer weekLow, Integer weekHigh,
            Double vol7, Double vol30,
            Double distTo7LowPct, Double distTo7HighPct,
            Double distTo30LowPct, Double distTo30HighPct,
            Double distTo6moLowPct, Double distTo6moHighPct)
        {
            this.id = id;
            this.name = name;
            this.qty = qty;
            this.currentHigh = currentHigh;
            this.weekLow = weekLow;
            this.weekHigh = weekHigh;

            // ---- Compute qty-aware Gain−Loss safely with long intermediates ----
            Integer gl = null;
            if (qty != null && qty > 0 && currentHigh != null && weekLow != null && weekHigh != null)
            {
                long term = 2L * currentHigh - ((long) weekLow + (long) weekHigh); // 2*current − (low+high)
                long glLong = (long) qty * term;

                // Clamp to Integer range to fit the table's Integer column
                if (glLong > Integer.MAX_VALUE)       gl = Integer.MAX_VALUE;
                else if (glLong < Integer.MIN_VALUE)  gl = Integer.MIN_VALUE;
                else                                   gl = (int) glLong;
            }
            this.gainLoss = gl;
            // --------------------------------------------------------------------

            this.vol7 = vol7;
            this.vol30 = vol30;
            this.distTo7LowPct = distTo7LowPct;
            this.distTo7HighPct = distTo7HighPct;
            this.distTo30LowPct = distTo30LowPct;
            this.distTo30HighPct = distTo30HighPct;
            this.distTo6moLowPct = distTo6moLowPct;     // ◀ assign
            this.distTo6moHighPct = distTo6moHighPct;
        }
    }




    static class SeriesStats
    {
        final Integer minLow;   // min of avgLowPrice over last 7 daily points
        final Integer maxHigh;  // max of avgHighPrice over last 7 daily points

        // Midpoint arrays used for volatility; newest last
        final List<Integer> mids7;   // last up to 7 mid prices (avg of low/high)
        final List<Integer> mids30;  // last up to 30 mid prices

        // Pre-computed volatilities (daily log-return std dev)
        final Double vol7;
        final Double vol30;

        // 7d mid extrema (for distance-to-low/high)
        final Integer minMid7;
        final Integer maxMid7;

        // 30d mid extrema (for distance-to-low/high)
        final Integer minMid30;
        final Integer maxMid30;

        final Integer minMid180;
        final Integer maxMid180;

        SeriesStats(Integer minLow, Integer maxHigh,
                    List<Integer> mids7, List<Integer> mids30,
                    Double vol7, Double vol30,
                    Integer minMid7, Integer maxMid7,
                    Integer minMid30, Integer maxMid30,
                    Integer minMid180, Integer maxMid180)   // ◀ add params
        {
            this.minLow = minLow;
            this.maxHigh = maxHigh;
            this.mids7 = mids7;
            this.mids30 = mids30;
            this.vol7 = vol7;
            this.vol30 = vol30;
            this.minMid7 = minMid7;
            this.maxMid7 = maxMid7;
            this.minMid30 = minMid30;
            this.maxMid30 = maxMid30;
            this.minMid180 = minMid180;   // ◀ assign
            this.maxMid180 = maxMid180;   // ◀ assign
        }
    }


}

