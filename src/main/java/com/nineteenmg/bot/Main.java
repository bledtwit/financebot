package com.nineteenmg.bot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        System.out.println("Main started");
        try {
            System.out.println("Creating TelegramBotsApi...");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            System.out.println("Creating MoneyBot...");
            MoneyBot bot = new MoneyBot();
            System.out.println("Registering bot...");
            botsApi.registerBot(bot);
            System.out.println("Бот запущен!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
