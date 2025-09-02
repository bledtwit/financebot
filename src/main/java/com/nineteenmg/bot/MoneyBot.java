package com.nineteenmg.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Обновлённый MoneyBot:
 * - поддерживает /usd, /btc, /eth (без аргумента: возвращают 1 USD/1BTC/1ETH курс)
 * - поддерживает /convert <amount> <symbol> (пример: /convert 0.3 eth)
 * - поддерживает короткие команды вида "/eth 0.3", "/usd 100", "/btc 0.01"
 * - безопасно обрабатывает TelegramApiException внутри sendMsg()
 */
public class MoneyBot extends TelegramLongPollingBot {

    private final String botToken;
    private final String exchangeApiKey;

    public MoneyBot() {
        Dotenv dotenv = Dotenv.load(); // загружаем .env
        this.botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
        this.exchangeApiKey = dotenv.get("EXCHANGE_API_KEY");

        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN не задан в .env!");
        }
        if (exchangeApiKey == null || exchangeApiKey.isBlank()) {
            System.out.println("Warning: EXCHANGE_API_KEY не задан в .env. Используется пустой ключ.");
        }
    }

    @Override
    public String getBotUsername() {
        return "Money_nineteenmgbot";
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            String chatId = update.getMessage().getChatId().toString();

            // --- 1) Обработка команды /convert или /torub ---
            String lower = text.toLowerCase();
            if (lower.startsWith("/convert") || lower.startsWith("/torub")) {
                String[] parts = text.split("\\s+");
                if (parts.length >= 3) {
                    String amountStr = parts[1].replace(",", ".");
                    String symbol = parts[2];
                    try {
                        double amount = Double.parseDouble(amountStr);
                        // Предполагается, что в ExchangeService есть метод convertToRub(symbol, amount)
                        String resp = ExchangeService.convertToRub(symbol, amount);
                        sendMsg(chatId, resp);
                    } catch (NumberFormatException nfe) {
                        sendMsg(chatId, "Неверный формат числа: " + parts[1] + ". Использование: /convert <amount> <symbol>");
                    }
                } else {
                    sendMsg(chatId, "Использование: /convert <amount> <symbol>\nПример: /convert 0.3 eth");
                }
                return; // обработали — не продолжаем дальше
            }

            // --- 2) Короткие формы: "/eth 0.3", "/btc 1", "/usd 100" ---
            // Формат: /symbol <amount>
            if (lower.matches("^/(eth|btc|usd)\\s+[-+]?\\d*[\\.,]?\\d+$")) {
                String[] parts = text.split("\\s+");
                String cmd = parts[0].substring(1).toLowerCase(); // eth / btc / usd
                String amountStr = parts[1].replace(",", ".");
                try {
                    double amount = Double.parseDouble(amountStr);
                    String resp;
                    if ("usd".equals(cmd)) {
                        // USD -> RUB
                        resp = ExchangeService.convertToRub("USD", amount);
                    } else if ("btc".equals(cmd)) {
                        resp = ExchangeService.convertToRub("BTC", amount);
                    } else { // eth
                        resp = ExchangeService.convertToRub("ETH", amount);
                    }
                    sendMsg(chatId, resp);
                } catch (NumberFormatException nfe) {
                    sendMsg(chatId, "Неверный формат числа: " + parts[1]);
                }
                return;
            }

            // --- 3) Обычные команды без аргументов ---
            String reply;
            switch (text) {
                case "/start" -> reply = """
                        Привет! Я личный финансовый бот @nineteenmg.
                        Доступные команды:
                        /usd — курс доллара к рублю
                        /btc — курс биткоина к доллару
                        /eth — курс эфира к доллару
                        
                        Дополнительно:
                        /convert <amount> <symbol> — конвертировать в рубли (пример: /convert 0.3 eth)
                        Также можно: "/eth 0.3", "/btc 1", "/usd 100"
                        """;
                case "/usd" -> reply = ExchangeService.getUsdRub();
                case "/btc" -> reply = ExchangeService.getBtcUsd();
                case "/eth" -> reply = ExchangeService.getEthUsd();
                default -> reply = "Неверная команда. Доступные: /start, /usd, /btc, /eth, /convert";
            }

            sendMsg(chatId, reply);
        }

    }

    /**
     * Helper: отправляет сообщение и безопасно ловит TelegramApiException
     */
    private void sendMsg(String chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        try {
            execute(msg); // TelegramApiException обрабатывается здесь
        } catch (TelegramApiException e) {
            e.printStackTrace();
            // можно дополнительно логировать или пытаться повторить
        }
    }
}
