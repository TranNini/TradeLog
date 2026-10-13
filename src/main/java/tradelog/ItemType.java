package tradelog;

public enum ItemType {
    SOLD, BOUGHT,
    // listed somewhere but not sold yet; price = asking price. Not counted in the sold and bought sums.
    FOR_SALE
}
