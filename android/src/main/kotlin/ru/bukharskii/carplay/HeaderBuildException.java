package ru.bukharskii.carplay;

public class HeaderBuildException extends Exception {

    public final String message;

    public HeaderBuildException(String message) {
        this.message = message;
    }
}
