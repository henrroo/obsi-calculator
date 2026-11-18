package com.kraken.obsi.index;

/**
 * The set in Eq. 2 is empty, so the utilized depth v_T and the index value are undefined. Per
 * methodology section 6 the calculation fails and no index value is published.
 */
public final class UtilizedDepthException extends Exception {

    public UtilizedDepthException(String message) {
        super(message);
    }
}
