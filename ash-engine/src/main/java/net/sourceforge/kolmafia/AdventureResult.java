package net.sourceforge.kolmafia;

public class AdventureResult {
    private int itemId;
    private int count;

    public AdventureResult() {
        this.itemId = 0;
        this.count = 0;
    }

    public AdventureResult(int itemId, int count) {
        this.itemId = itemId;
        this.count = count;
    }

    public int getItemId() { return itemId; }
    public void setItemId(int itemId) { this.itemId = itemId; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}