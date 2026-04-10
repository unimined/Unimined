package com.example;

import net.flintloader.loader.FlintLoader;
import net.flintloader.loader.api.FlintModule;
import net.flintloader.loader.api.event.client.SinglePlayerEvent;
import net.flintloader.loader.core.event.annot.EventBusListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements FlintModule {

    public static Logger LOGGER = LoggerFactory.getLogger(ExampleMod.class);

    @Override
    public void initializeModule() {
        LOGGER.info("Initializing ExampleMod");
        FlintLoader.eventBus().registerEventListener(this);
    }

    @EventBusListener
    public static void playerJoinEvent(SinglePlayerEvent.PlayerLogin event) {
        LOGGER.info("{} has joined the game", event.getPlayer().getDisplayName().getString());
    }
}
