package com.zin.jadxaimcp.server;

public class CurrentClassView {
    private final String name;
    private final String content;

    public CurrentClassView(String name, String content) {
        this.name = name;
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }
}
