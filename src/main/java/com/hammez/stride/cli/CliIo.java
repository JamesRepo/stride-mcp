package com.hammez.stride.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/** The console streams the commands use. Tests swap in their own. */
public record CliIo(BufferedReader in, PrintStream out, PrintStream err) {

    public static CliIo system() {
        return new CliIo(new BufferedReader(new InputStreamReader(System.in)), System.out, System.err);
    }
}
