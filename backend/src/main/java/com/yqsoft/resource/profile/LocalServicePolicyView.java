package com.yqsoft.resource.profile;

import java.util.List;

public record LocalServicePolicyView(
        String purpose, String version, String title, List<Section> sections) {
    public record Section(String heading, String content) {}
}
