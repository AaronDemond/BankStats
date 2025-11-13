# BankStats Plugin

Helps track and analyze the price changes of every item in your bank.


## Features

- 📊 Monitors price changes of items in your bank over time.
- 📈 Provides detailed statistics and trends for each item.
- 💹 Quickly identify items that have increased or decreased in value, using sortable charts. 
- 🔍 supports large, popup charts for greater detail and analysis.
- 🕓 Track prices at any interval you choose - daily, weekly, hourly, Etc.
- 💾 Stores any number of snapshots locally for later comparison.

## Usage

The plugin works by comparing the current value of 
your items against a previous *snapshot* taken at 
an earlier time. At the same time, real time market 
data is provided for each item in your bank, after 
you update by pressing "Update from bank".

The basic workflow is as follows (TL;DR at the bottom):

- Open your bank (This is a requirement to sync items).
- press the "Update from bank" button in the controls section of the sidebar panel.
- Wait for the plugin to process your bank items. A counter will show progress.
- Now, the "Price Data" table will populate, giving you real time market prices and statistics.
- To compare your current bank to an earlier point in time, click **Load** and select a snapshot file.
    - The **Gain / Loss** table will automatically populate with the differences.
- To record a new reference point, click **Save** to create a new snapshot.
  - Snapshots are stored locally on your machine.
  - You can take as many as you like (daily, weekly, etc.).
  - Remember: snapshots are based on your most recent import.  
  If your bank has changed, re-import before saving.

## TL;DR

1. Open bank.
2. Import items
3. Save snapshot or load snapshot. (Save if you want something to reference later, load if you want to compare now). When you load a snapshot the Gain / Loss table is automatically populated.
4. To repeat step 3, the safest thing to do is re import from your bank. This automatically refreshes the items loaded and their prices.

### Notes
- Uses the official [OSRS Wiki Prices API](https://prices.runescape.wiki)
- All data is fetched anonymously; nothing is uploaded or shared
- Compatible with RuneLite v1.11+ and Java 11  

