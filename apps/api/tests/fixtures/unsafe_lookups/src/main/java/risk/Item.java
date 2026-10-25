package risk;

public class Item {
    private final int id;
    private final double score;
    private final String code;

    public Item(int id, double score, String code) {
        this.id = id;
        this.score = score;
        this.code = code;
    }

    public int getId() {
        return id;
    }

    public double getScore() {
        return score;
    }

    public String getCode() {
        return code;
    }

    public int nextRank() {
        return id + 1;
    }
}
