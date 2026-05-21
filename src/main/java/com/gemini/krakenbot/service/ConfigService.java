package com.gemini.krakenbot.service;
import java.io.IOException;

import com.gemini.krakenbot.config.AppConfig;

public interface ConfigService {

    void loadConfig() throws IOException;
    AppConfig getConfig();
    void updateConfig(AppConfig newConfig);

}
