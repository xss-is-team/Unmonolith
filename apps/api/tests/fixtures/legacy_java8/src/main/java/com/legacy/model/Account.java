package com.legacy.model;

public class Account implements Identifiable {
    private final int id;
    private final String owner;
    private long balanceCents;

    public Account(int id, String owner, long balanceCents) {
        this.id = id;
        this.owner = owner;
        this.balanceCents = balanceCents;
    }

    @Override
    public int getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public void deposit(long cents) {
        balanceCents += cents;
    }
}
