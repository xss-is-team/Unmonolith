package com.legacy.util;

/** Small textbook algorithms with known asymptotic behaviour. */
public final class Algorithms {
    private Algorithms() {
    }

    public static int identity(int x) {
        return x;
    }

    public static int sum(int[] values) {
        int total = 0;
        for (int i = 0; i < values.length; i++) {
            total += values[i];
        }
        return total;
    }

    public static int countEqualPairs(int[] left, int[] right) {
        int pairs = 0;
        for (int l : left) {
            for (int r : right) {
                if (l == r) {
                    pairs++;
                }
            }
        }
        return pairs;
    }

    public static int binarySearch(int[] sorted, int key) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < key) {
                low = mid + 1;
            } else if (sorted[mid] > key) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    public static long fibonacci(int n) {
        if (n < 2) {
            return n;
        }
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
}
