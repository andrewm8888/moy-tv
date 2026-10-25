package ru.mikulinskiy.tvhome;

/** Коды погоды Open-Meteo (WMO) в эмодзи и текст. */
final class Weather {
    private Weather() {}

    static String icon(int code, boolean night) {
        if (code == 0) return night ? "🌙" : "☀️";
        if (code == 1 || code == 2) return night ? "☁️" : "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }

    static String text(int code) {
        if (code == 0) return "Ясно";
        if (code == 1) return "Преимущественно ясно";
        if (code == 2) return "Переменная облачность";
        if (code == 3) return "Пасмурно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Морось";
        if (code >= 61 && code <= 67) return "Дождь";
        if (code >= 71 && code <= 77) return "Снег";
        if (code >= 80 && code <= 82) return "Ливень";
        if (code == 85 || code == 86) return "Снегопад";
        if (code >= 95) return "Гроза";
        return "";
    }
}
