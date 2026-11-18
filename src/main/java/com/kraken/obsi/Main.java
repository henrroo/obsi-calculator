package com.kraken.obsi;

import java.util.List;

/** Entry point of the runnable JAR. See {@link Cli} for the command-line contract. */
public final class Main {

    private Main() {
    }

    static void main(String[] args) {
        System.exit(new Cli().run(List.of(args), System.out, System.err));
    }
}
