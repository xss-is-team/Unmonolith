package com.legacy.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.legacy.model.Account;
import com.legacy.util.Algorithms;

public class Ledger {
    private final List<Account> accounts = new ArrayList<Account>();

    public void open(Account account) {
        accounts.add(account);
    }

    /** Linear: one pass, the getter lives in another file. */
    public long totalBalance(List<Account> list) {
        long total = 0;
        for (Account account : list) {
            total += account.getBalanceCents();
        }
        return total;
    }

    public Account find(int id) {
        for (Account account : accounts) {
            if (account.getId() == id) {
                return account;
            }
        }
        return null;
    }

    public int[] sortedIds() {
        List<Account> copy = new ArrayList<Account>(accounts);
        Collections.sort(copy, new Comparator<Account>() {
            @Override
            public int compare(Account a, Account b) {
                return Integer.compare(a.getId(), b.getId());
            }
        });
        int[] ids = new int[copy.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = copy.get(i).getId();
        }
        return ids;
    }

    public boolean contains(int id) {
        return Algorithms.binarySearch(sortedIds(), id) >= 0;
    }
}
