package com.hammez.stride.cli;

/** The command line is wrong. Exit code 2. */
class UsageException extends RuntimeException {

    private final boolean showUsage;

    /**
     * @param message   what's wrong and how to fix it, or null to show only the usage
     * @param showUsage true to print the usage after the message. False when the message alone says what to do,
     *                  such as "Dates look like 2026-10-22."
     */
    UsageException(String message, boolean showUsage) {
        super(message);
        this.showUsage = showUsage;
    }

    boolean showUsage() {
        return showUsage;
    }
}
