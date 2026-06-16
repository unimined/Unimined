package com.example.liteloader;

import com.mumfrey.liteloader.LiteMod;

import java.io.File;

public class LiteModExample implements LiteMod {
    @Override
    public String getVersion() {
        return "0.0.0";
    }

    @Override
    public void init(File file) {

    }

    @Override
    public void upgradeSettings(String string, File file, File file2) {

    }

    @Override
    public String getName() {
        return "Example Mod";
    }

}
