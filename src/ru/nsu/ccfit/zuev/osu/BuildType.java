package ru.nsu.ccfit.zuev.osu;

import app.mosu.BuildConfig;

public class BuildType {
    public static boolean hasOnlineAccess() {
        return BuildConfig.BUILD_TYPE.matches("(release|pre_release)");
    }
}
