package com.hammez.stride;

import org.slf4j.LoggerFactory;

/**
 * Test only: prints to stdout and logs a warning while the app starts, like a stray {@code println} would. Not a
 * {@code @Component}, so it's only loaded when a test passes {@code --spring.main.sources=com.hammez.stride.NoisyStartup}.
 */
class NoisyStartup {

    static final String PRINTED = "a stray println";
    static final String LOGGED = "a stray warning";

    NoisyStartup() {
        System.out.println(PRINTED);
        LoggerFactory.getLogger(NoisyStartup.class).warn(LOGGED);
    }
}
