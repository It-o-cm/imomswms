package com.intermarche.backoffice.domain.store;

public interface Location {

    default String getAisle() {
        return null;
    }

    default String getArea() {
        return null;
    }

}
