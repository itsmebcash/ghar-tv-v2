package com.ghar.player.shared;

/**
 * How Bcash Player (phone) and Ghar Player (TV) talk on the home network. See ARCHITECTURE.md.
 *
 * The phone is the brain: library, playlists and every setting live there. The TV is a simple
 * speaker: it plays the queue the phone sends, streaming each file from the phone over Wi-Fi.
 */
public final class Protocol {

    private Protocol() {
    }

    /** DNS-SD service the TV announces, so phones find it without typing an address. */
    public static final String SERVICE_TYPE = "_gharplayer._tcp.";
    /** Ports the TV tries, in order. */
    public static final int[] TV_PORTS = {8810, 8811, 8812, 8813, 0};
    /** Ports the phone's song server tries. */
    public static final int[] PHONE_PORTS = {8820, 8821, 8822, 8823, 0};

    public static final String HEADER_TOKEN = "X-Ghar-Token";
    public static final int VERSION = 2;

    /** Anyone: {app, v, deviceId, name}. */
    public static final String HELLO = "/api/hello";
    /** {code, phoneId, phoneName} → {token}. Only while the TV screen shows a code. */
    public static final String PAIR = "/api/pair";

    // Everything below needs the X-Ghar-Token header.

    /** {phone, phoneName, items[], index, position, play, settings{}} – replace the queue and start. */
    public static final String LOAD = "/api/load";
    /** {action: play|pause|toggle|seek(position)|jump(index, position)|next|previous|stop}. */
    public static final String CONTROL = "/api/control";
    /** {action: add(at, items[]) | move(from, to, newIndex) | remove(from, to)}. */
    public static final String QUEUE = "/api/queue";
    /** {items:[{id, repeat, speed, gain}]} – change settings of queued songs. */
    public static final String META = "/api/meta";
    /** {loop, fadeMs, eq, evenOut, single}. */
    public static final String SETTINGS = "/api/settings";
    /** → {id, index, position, duration, playing, ended, speed, repeatDone, count, message}. */
    public static final String STATE = "/api/state";
}
