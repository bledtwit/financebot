package com.nineteenmg.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ExchangeService {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ключ для fiat (USD -> RUB)
    private static final String API_KEY = "28a792e865f6c881097a0e74";
    private static final String USD_URL = "https://v6.exchangerate-api.com/v6/" + API_KEY + "/latest/USD";

    // CoinGecko — бесплатный публичный endpoint для крипты (возвращает BTC и ETH к USD)
    private static final String COINGECKO_URL = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd";

    // Кэш (ключ: "FROM_TO"), TTL = 5000 ms (5 секунд).
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 5_000L; // <-- с на 5 секунд

    private static class Cached {
        final double rate;
        final long ts; // epoch millis when fetched

        Cached(double rate, long ts) {
            this.rate = rate;
            this.ts = ts;
        }
    }
    private static String getApiKey() {
        String key = System.getenv("EXCHANGE_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("Warning: EXCHANGE_API_KEY not set! Using default key.");
            key = ""; //  запасной ключ
        }
        return key;
    }


    // ---------- Public methods used by bot ----------
    public static String getUsdRub() {
        return getCachedOrFetch("USD_RUB", () -> fetchUsdRub());
    }

    public static String getBtcUsd() {
        return getCachedOrFetch("BTC_USD", () -> fetchCryptoRatesAndReturn("BTC"));
    }

    public static String getEthUsd() {
        return getCachedOrFetch("ETH_USD", () -> fetchCryptoRatesAndReturn("ETH"));
    }

    // ---------- Generic cache helper ----------
    private static String getCachedOrFetch(String key, Fetcher fetcher) {
        try {
            long now = System.currentTimeMillis();
            Cached c = CACHE.get(key);
            if (c != null && (TTL_MS == 0 || now - c.ts < TTL_MS)) {
                // возвращаем закэшированное значение (время — время последнего обновления)
                return formatFromKey(key, c.rate, c.ts, "cache");
            }

            // fetch: fetcher обязан положить значение в кэш
            return fetcher.fetch();
        } catch (Exception e) {
            e.printStackTrace();
            return "Ошибка получения курса";
        }
    }

    // ---------- Fetchers ----------
    private static String fetchUsdRub() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(USD_URL))
                    .header("User-Agent", "Mozilla/5.0 (bot)")
                    .GET()
                    .build();

            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Ошибка API (USD): HTTP " + resp.statusCode();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode conv = root.path("conversion_rates");
            if (conv.isMissingNode() || conv.get("RUB") == null) {
                return "Ошибка: в ответе нет RUB (USD→RUB)\n" + resp.body();
            }

            double rate = conv.get("RUB").asDouble();
            long now = System.currentTimeMillis();
            CACHE.put("USD_RUB", new Cached(rate, now));
            return formatFromKey("USD_RUB", rate, now, "exchangerate-api.com");
        } catch (Exception e) {
            e.printStackTrace();
            return "Не удалось получить USD→RUB: " + e.getMessage();
        }
    }

    // Запрашиваем CoinGecko один раз и кладём в кеш сразу BTC_USD и ETH_USD
    private static String fetchCryptoRatesAndReturn(String coin) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(COINGECKO_URL))
                    .header("User-Agent", "Mozilla/5.0 (bot)")
                    .GET()
                    .build();

            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Ошибка API (crypto): HTTP " + resp.statusCode();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode btcNode = root.path("bitcoin").path("usd");
            JsonNode ethNode = root.path("ethereum").path("usd");

            if (btcNode.isMissingNode() || ethNode.isMissingNode()) {
                return "Ошибка: неожиданный ответ CoinGecko\n" + resp.body();
            }

            double btcUsd = btcNode.asDouble();
            double ethUsd = ethNode.asDouble();
            long now = System.currentTimeMillis();
            CACHE.put("BTC_USD", new Cached(btcUsd, now));
            CACHE.put("ETH_USD", new Cached(ethUsd, now));

            if ("BTC".equalsIgnoreCase(coin)) {
                return formatFromKey("BTC_USD", btcUsd, now, "CoinGecko");
            } else {
                return formatFromKey("ETH_USD", ethUsd, now, "CoinGecko");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Не удалось получить курс криптовалют: " + e.getMessage();
        }
    }

    // ---------- Formatting ----------
    private static String formatFromKey(String key, double rate, long epochMillis, String source) {
        String timeIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
        if (key.endsWith("_RUB")) {
            return String.format(Locale.US, "1 USD ≈ %.4f RUB (source: %s, UTC %s)", rate, source, timeIso);
        } else if (key.startsWith("BTC_")) {
            return String.format(Locale.US, "1 BTC ≈ %.2f USD (source: %s, UTC %s)", rate, source, timeIso);
        } else if (key.startsWith("ETH_")) {
            return String.format(Locale.US, "1 ETH ≈ %.2f USD (source: %s, UTC %s)", rate, source, timeIso);
        } else {
            return String.format(Locale.US, "%.4f (source: %s, UTC %s)", rate, source, timeIso);
        }
    }
    public static double getRateValue(String from, String to) {
        String key = (from + "_" + to).toUpperCase();

        // попробуем взять из кеша (CACHE доступна в этом классе)
        Cached c = CACHE.get(key);
        if (c != null) return c.rate;

        // если нет в кеше — вызовем соответствующий публичный метод, который заполнит кеш
        try {
            if ("USD".equalsIgnoreCase(from) && "RUB".equalsIgnoreCase(to)) {
                // вызов пополняет CACHE
                getUsdRub();
            } else if ("BTC".equalsIgnoreCase(from) && "USD".equalsIgnoreCase(to)) {
                getBtcUsd();
            } else if ("ETH".equalsIgnoreCase(from) && "USD".equalsIgnoreCase(to)) {
                getEthUsd();
            } else if ("ETH".equalsIgnoreCase(from) && "RUB".equalsIgnoreCase(to)) {
                // ETH -> RUB = ETH->USD * USD->RUB
                // сначала пополним обе
                getEthUsd();
                getUsdRub();
            } else if ("BTC".equalsIgnoreCase(from) && "RUB".equalsIgnoreCase(to)) {
                getBtcUsd();
                getUsdRub();
            } else if ("USD".equalsIgnoreCase(from) && "USD".equalsIgnoreCase(to)) {
                return 1.0;
            } else {
                // не поддерживаемую пару можно попытаться получить напрямую через API (не реализовано)
                return Double.NaN;
            }
        } catch (Exception ignored) {}

        // повторная проверка кеша / вычисление для cross-pairs
        c = CACHE.get(key);
        if (c != null) return c.rate;

        // если требуется cross (ETH->RUB или BTC->RUB), соберём из двух курсов
        if ("ETH".equalsIgnoreCase(from) && "RUB".equalsIgnoreCase(to)) {
            Cached ethUsd = CACHE.get("ETH_USD");
            Cached usdRub = CACHE.get("USD_RUB");
            if (ethUsd != null && usdRub != null) return ethUsd.rate * usdRub.rate;
        }
        if ("BTC".equalsIgnoreCase(from) && "RUB".equalsIgnoreCase(to)) {
            Cached btcUsd = CACHE.get("BTC_USD");
            Cached usdRub = CACHE.get("USD_RUB");
            if (btcUsd != null && usdRub != null) return btcUsd.rate * usdRub.rate;
        }

        return Double.NaN;
    }

    /**
     * Конвертирует amount единиц symbol в рубли и возвращает понятную строку.
     * Поддерживаем symbol = "usd"|"btc"|"eth" (регистр не важен).
     */
    public static String convertToRub(String symbol, double amount) {
        symbol = symbol.trim().toUpperCase();
        try {
            double result;
            if ("USD".equals(symbol)) {
                double rate = getRateValue("USD", "RUB");
                if (Double.isNaN(rate)) return "Не удалось получить курс USD→RUB";
                result = amount * rate;
                return String.format(Locale.US, "%.6f USD ≈ %.4f RUB (по курсу %.4f)", amount, result, rate);
            } else if ("BTC".equals(symbol)) {
                // BTC -> USD -> RUB
                double btcUsd = getRateValue("BTC", "USD");
                double usdRub = getRateValue("USD", "RUB");
                if (Double.isNaN(btcUsd) || Double.isNaN(usdRub)) return "Не удалось получить курс BTC→USD или USD→RUB";
                result = amount * btcUsd * usdRub;
                return String.format(Locale.US, "%.6f BTC ≈ %.2f USD ≈ %.4f RUB (BTC→USD=%.2f, USD→RUB=%.4f)", amount, amount * btcUsd, result, btcUsd, usdRub);
            } else if ("ETH".equals(symbol)) {
                double ethUsd = getRateValue("ETH", "USD");
                double usdRub = getRateValue("USD", "RUB");
                if (Double.isNaN(ethUsd) || Double.isNaN(usdRub)) return "Не удалось получить курс ETH→USD или USD→RUB";
                result = amount * ethUsd * usdRub;
                return String.format(Locale.US, "%.6f ETH ≈ %.2f USD ≈ %.4f RUB (ETH→USD=%.2f, USD→RUB=%.4f)", amount, amount * ethUsd, result, ethUsd, usdRub);
            } else {
                return "Неподдерживаемая валюта: " + symbol + ". Поддерживается USD, BTC, ETH.";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Ошибка при конвертации: " + e.getMessage();
        }
    }
    private interface Fetcher {
        String fetch() throws Exception;
    }
}
