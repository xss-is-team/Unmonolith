package ext;

import org.apache.commons.lang3.StringUtils;

public class Report {
    public String title(String raw) {
        return StringUtils.capitalize(raw.trim());
    }

    public int plain(int x) {
        return x + 1;
    }
}
