package com.legacy.app;

import com.legacy.model.Account;
import com.legacy.service.Ledger;
import com.legacy.util.Algorithms;

public class Main {
    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        ledger.open(new Account(30, "carol", 1200));
        ledger.open(new Account(10, "alice", 500));
        ledger.open(new Account(20, "bob", 700));
        System.out.println("found=" + (ledger.find(20) != null));
        System.out.println("contains=" + ledger.contains(10));
        System.out.println("fib=" + Algorithms.fibonacci(15));
    }
}
