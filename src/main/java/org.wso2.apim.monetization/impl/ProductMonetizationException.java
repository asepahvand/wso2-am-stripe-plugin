package org.wso2.apim.monetization.impl;

public class ProductMonetizationException extends Exception {

    public ProductMonetizationException(String msg) {
        super(msg);
    }

    public ProductMonetizationException(String msg, Throwable e) {
        super(msg, e);
    }

    public ProductMonetizationException(Throwable throwable) {
        super(throwable);
    }

}