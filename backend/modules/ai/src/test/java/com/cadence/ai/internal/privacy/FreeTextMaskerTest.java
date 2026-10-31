package com.cadence.ai.internal.privacy;

import com.cadence.platform.phone.PhoneShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeTextMaskerTest {

    private final FreeTextMasker masker = new FreeTextMasker();

    private String mask(String text) {
        return masker.mask(text, NameIndex.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+995 555 12 34 56",
            "8 (916) 123-45-67",
            "+420 777 123 456",
            "555-1234567",
            "+7(916)1234567",
            "555123456",
            "+995555123456",
            "123-45-67",
            "777.123.456"
    })
    void phonesInCommonFormatsBecomePlaceholders(String phone) {
        assertEquals("Call [phone] today", mask("Call " + phone + " today"));
        assertEquals("[phone].", mask(phone + "."));
    }

    @Test
    void emailsAndUrlsBecomePlaceholders() {
        assertEquals("Write to [email], or [email]",
                mask("Write to anna.ivanova+salon@gmail.com, or NINO_B@Example.GE"));
        assertEquals("See [url] and [url] and [url]",
                mask("See https://instagram.com/anna_iv?x=1 and www.example.ge/page and facebook.com/anna.iv"));
        assertEquals("Telegram [url]", mask("Telegram @anna_ivanova"));
    }

    @Test
    void addressesInRussianEnglishAndCzech() {
        assertEquals("Живёт [address], [address], [address], [address], [address]",
                mask("Живёт ул. Ленина, д. 5, кв. 12, подъезд 3, домофон 45К"));
        assertEquals("Живёт [address] на [address]", mask("Живёт ул.Большая Садовая 10 на 3 этаже"));
        assertEquals("Lives at [address], [address]", mask("Lives at 12 Baker Street, apt 4"));
        assertEquals("Lives on [address]", mask("Lives on Rustaveli str. 12"));
        assertEquals("Bydlí [address], [address]", mask("Bydlí ulice Národní 12, byt 5"));
        assertEquals("Dům [address]", mask("Dům č.p. 123"));
        assertEquals("Вулиця: [address]", mask("Вулиця: вул. Хрещатик 22"));
        assertEquals("[address], Москва", mask("123456, Москва"));
        assertEquals("[address] Praha", mask("110 00 Praha"));
        assertEquals("[address]", mask("индекс 123456"));
        assertEquals("[address]", mask("PSČ 110 00"));
    }

    @Test
    void stopWordClausesInEveryLocaleBecomeSensitive() {
        assertEquals("Prefers mornings. [sensitive].", mask("Prefers mornings. Allergic to latex, uses gel."));
        assertEquals("[sensitive]; pays cash", mask("Owes 50 GEL from last visit; pays cash"));
        assertEquals("Любит кофе; [sensitive]",
                mask("Любит кофе; беременна, просит мягкий шампунь"));
        assertEquals("[sensitive]\nПриходить вчасно", mask("Має алергію на латекс\nПриходить вчасно"));
        assertEquals("[sensitive]", mask("Je těhotná, chce ranní termíny"));
        assertEquals("[sensitive]. Любит чай", mask("Есть долг 30 лари. Любит чай"));
        assertEquals("[sensitive]", mask("აქვს ალერგია"));
        assertEquals("[sensitive]", mask("DIABETES type 2, prefers mornings"));
    }

    @Test
    void exactStopWordsDoNotMaskLookAlikes() {
        String text = "Долго ждала, she has an illustrated card and pillows, ракетка новая";
        // "долг" (debt), "рак" (cancer), "ill" and "pill" are whole-word only, so these look-alikes stay.
        assertEquals(text, mask(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Book on 27.10 10:00 for 80 GEL, 45 min",
            "Free between 10:30-11:15 and 14:00-15:30",
            "Visit on 2026-10-27T10:00, then 27.10.2026 10:00 and 10/27/2026",
            "From 27.10-28.10 the salon is closed",
            "Revenue 12500.50 GEL, 3 visits, 1h 30m",
            "Prices: 80, 120, 15000 GEL",
            "Anna asked to move her haircut to Friday"
    })
    void ordinaryTextIsUnchanged(String text) {
        assertEquals(text, mask(text));
    }

    @Test
    void knownNamesBecomeRefsAfterTheOtherPasses() {
        Map<String, String> known = new LinkedHashMap<>();
        known.put("CLIENT_1", "Anna Ivanova");
        known.put("CLIENT_2", "Anna Petrova");
        known.put("MASTER_1", "Nino Beridze");
        NameIndex names = NameIndex.of(known);

        assertEquals("CLIENT_1 prefers MASTER_1. [sensitive]",
                masker.mask("anna ivanova prefers Nino. Pregnant, Anna Ivanova asked", names));
        assertEquals("Sister (CLIENT_1|CLIENT_2) called", masker.mask("Sister Anna called", names));
        assertEquals("Annabel and Ninotchka stay", masker.mask("Annabel and Ninotchka stay", names));
    }

    @Test
    void patternsOnlyLeavesStopWordsAlone() {
        assertEquals("Who has an allergy note? Call [phone]",
                masker.maskPatterns("Who has an allergy note? Call +995 555 12 34 56"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''|''",
            "'   '|'   '"
    })
    void blankTextIsReturnedAsIs(String in, String out) {
        assertEquals(out, mask(in));
        assertEquals(null, mask(null));
    }

    @Test
    void dotFormattedPhonesThatStartLikeADateAreStillPhones() {
        assertEquals("Call [phone]", mask("Call 06.12.34.56.78"));
        assertEquals("Call [phone]", mask("Call 12.10.45.67.89"));
    }

    @Test
    void latinAbbreviationsOfRussianAddressWordsAreAddresses() {
        assertEquals("[address], [address]", mask("ul. Rustaveli 5, kv. 12"));
    }

    @Test
    void longPathologicalInputsNeitherOverflowNorHang() {
        String[] units = {"1 ", "a.", "Abc ", "ул. ", "a@b.", "(1)", "1.", "x", "a-", "a'", "1,", "@a.", "Abc 1-2 "};
        for (String unit : units) {
            String text = unit.repeat(16000 / unit.length());
            long started = System.nanoTime();
            String masked = masker.mask(text + "@", NameIndex.empty());
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(millis < 10_000, unit + " took " + millis + " ms");
            assertFalse(masked.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+7\u00A0916\u00A0123\u00A045\u00A067",
            "+7\u202F916\u202F123\u202F45\u202F67",
            "+7\u2009916\u2009123\u200945\u200967",
            "+7 916 - 123 - 45 - 67",
            "916   123   45   67",
            "916\t123\t45\t67",
            "916/123/45/67",
            "+7 916,123,45,67",
            "916,123,45,67",
            "+7 916_123_45_67",
            "+7 916  123   45 67",
            "+7 (916) 123 - 45 - 67",
            "+7/916/1234567",
            "+7 ( 916 ) 123 45 67",
            "+7 916 123\u200B45\u200B67",
            "8\u2011916\u2011123\u201145\u201167",
            "916\u2212123\u221245\u221267",
            "916\u00B7123\u00B745\u00B767",
            "916\u2022123\u202245\u202267",
            "916~123~45~67",
            "916:123:45:67",
            "916|123|45|67",
            "916*123*45*67",
            "916\\123\\45\\67",
            "916\n123\n45\n67",
            "916---123---45---67",
            "916, 123, 45, 67",
            "+995 55 12.03.56",
            "+7 916 123 12:10",
            "06 12.10.20",
            "77 12.10.2026",
            "916) 123-45-67",
            "[916] 123 45 67",
            "\uD835\uDFD7\uD835\uDFCF\uD835\uDFD4 \uD835\uDFCF\uD835\uDFD0\uD835\uDFD1 \uD835\uDFD2\uD835\uDFD3",
            "+\u0669\u0669\u0665 \u0665\u0665\u0665 \u0661\u0662 \u0663\u0664 \u0665\u0666",
            "\uFF19\uFF11\uFF16 \uFF11\uFF12\uFF13 \uFF14\uFF15 \uFF16\uFF17"
    })
    void phonesWithAnySeparatorLeaveNoTail(String phone) {
        assertEquals("Call [phone] today", mask("Call " + phone + " today"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+995 555 12.03.56",
            "+420 777 12.10.20",
            "+7 916 1234-12-12",
            "(495) 1234-12-12",
            "916 123 12.10",
            "8 916 12-12-2026"
    })
    void dateShapedTailsOfPhonesAreNotShielded(String phone) {
        assertEquals("Call [phone]", mask("Call " + phone));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "27.10.2026",
            "27.10 10:00",
            "10:30-11:15",
            "12500.50",
            "10,5",
            "1,5 часа",
            "1,200,000 GEL",
            "Цены 10,20,30,40",
            "Prices: 80, 120, 15000 GEL",
            "10.00-11.30",
            "10.30 11.30 12.30",
            "5 27.10.2026",
            "5 услуг",
            "Haircut 45 min",
            "80 GEL",
            "12 визитов",
            "Стрижка 45, Окрашивание 120",
            "Book Anna Friday 10-12",
            "Haircut 10-15 min",
            "В 2026 Анна пришла",
            "v1.2.3",
            "Она должна прийти в 10",
            "ул",
            "Запись на 10:00, 11:00, 12:00",
            "10:30-11:15, 12:00-13:00",
            "27.10, 28.10, 29.10",
            "27.10 10:00, 28.10 12:00",
            "Revenue 12500.50 GEL",
            "пр. к врачу",
            "на улице холодно",
            "Haircut 45m",
            "Стрижка 45, Окрашивание 60",
            "Tbilisi 1500 GEL"
    })
    void numbersInOrdinaryContextsStay(String text) {
        assertEquals(text, mask(text));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Иванов из 42 квартиры, домофон 89161234567|Иванов из [address], домофон [phone]",
            "Иванов из 42 квартиры|Иванов из [address]",
            "Сосед из 42 кв.|Сосед из [address]",
            "живёт в доме 5|живёт в [address]",
            "Тверская 12 кв 5|[address]",
            "ул. Тверская 12 кв 5|[address]",
            "Москва, ул. Ленина, 5|Москва, [address]",
            "Vinohradská 1234/56|[address]",
            "Národní 12, Praha 1|[address]",
            "Rustaveli Ave 12|[address]",
            "Lenina 5-12|[address]",
            "0108 Tbilisi|[address] Tbilisi",
            "пр. Мира 5|[address]",
            "пл. Ленина 3|[address]",
            "б-р Шевченко 10|[address]",
            "наб. Фонтанки 5|[address]",
            "ш. Энтузиастов 5|[address]",
            "на улицу Ленина 5|на [address]",
            "улицы Ленина 5|[address]",
            "вулицею Хрещатик 22|[address]",
            "12 Baker St|[address]",
            "Flat 4, 12 Baker St|[address], [address]",
            "Тверская, 12, кв. 5|[address]",
            "Москва, Тверская, 12-5|Москва, [address]",
            "Ленина 12 корпус 2 кв 5|[address]",
            "Tbilisi 0108|Tbilisi [address]",
            "0108, Tbilisi|[address], Tbilisi",
            "Praha 110 00|Praha [address]",
            "Rustaveli 12a|[address]",
            "Тверская 12, Воронеж|[address]",
            "Národní 12, Kolín|[address]"
    })
    void addressesWithoutClassicKeywords(String text, String expected) {
        assertEquals(expected, mask(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Аллергия: латекс, никель",
            "Беременна, 20 недель",
            "Варикоз на левой ноге",
            "Псориаз локтей",
            "дерматит",
            "Экзема рук",
            "Герпес на губе",
            "Гипертония",
            "Кардиостимулятор",
            "Kardiostimulátor",
            "Принимает роаккутан",
            "isotretinoin",
            "Панические атаки",
            "Тромбоз",
            "Ступня: psoriasis",
            "Хворіє на екзему",
            "Vaří: lupénka",
            "ეგზემა",
            "Она должна 5000",
            "Должен салону 300 лари",
            "Аллергия на препарат 2.5 мг, утром",
            "Латекс и никель, аллергия",
            "Шизофрения",
            "Аборт в 2020",
            "Биполярное расстройство",
            "Алкоголизм",
            "Эндометриоз",
            "Кормит грудью",
            "Высокое давление",
            "Месячные",
            "Сколиоз",
            "Судимость",
            "Банкротство",
            "scoliosis",
            "Schizophrenia",
            "Bankrupt",
            "Breastfeeding",
            "Ендометріоз",
            "Судимість",
            "Alkoholik",
            "Skolióza",
            "შიზოფრენია"
    })
    void salonHealthAndDebtsMaskTheWholeSentence(String text) {
        assertEquals("[sensitive]", mask(text));
    }

    @Test
    void masksTheWholeSentenceHoldingAStopWord() {
        assertEquals("[sensitive]. Pays cash", mask("Prefers mornings, allergic to latex, uses gel. Pays cash"));
        assertEquals("[sensitive]; Любит чай\nНовая строка", mask("Аллергия: латекс, никель; Любит чай\nНовая строка"));
        // A detail in the sentence before stays: the mask has no idea it belongs to the next one (documented residue).
        assertEquals("Никель. [sensitive]", mask("Никель. Аллергия"));
    }

    @Test
    void obfuscatedAndAccentedEmails() {
        assertEquals("Mail [email].", mask("Mail jose\u0301@x.com."));
        assertEquals("Mail [email]", mask("Mail petr.novák@seznam.cz"));
        assertEquals("Mail [email]", mask("Mail petr.nova\u0301k@seznam.cz"));
        assertEquals("Mail [email]", mask("Mail anna.o'brien@gmail.com"));
        assertEquals("Mail [email]", mask("Mail anna(at)mail.ru"));
        assertEquals("Mail [email]", mask("Mail anna@mail"));
    }

    @Test
    void handlesAndHostsAreMaskedWhole() {
        assertEquals("Ig [url]", mask("Ig @anna.petrova"));
        assertEquals("Ig [url].", mask("Ig @petrova.nails."));
        assertEquals("See [url]", mask("See nails-anna.sk/kontakt"));
        assertEquals("See [url]", mask("See anna.petrova.pl"));
    }

    @Test
    void refsTypedInANoteAreNotLiveRefs() {
        NameIndex names = NameIndex.of(Map.of("CLIENT_1", "Anna Ivanova"));
        assertEquals("[ref] and CLIENT_1 and [ref]", masker.mask("CLIENT_3 and Anna and client_7", names));
    }

    @Test
    void refsDoNotGlueToNeighbouringDigitsOrUnderscores() {
        NameIndex names = NameIndex.of(Map.of("CLIENT_1", "Anna Ivanova", "CLIENT_2", "Olga Sokolova"));
        assertEquals("CLIENT_1 1990", names.apply("Anna1990"));
        assertEquals("CLIENT_2 2 CLIENT_1", names.apply("Olga2Anna"));
        assertEquals("CLIENT_1", names.apply("Anna_Ivanova"));
        assertEquals("CLIENT_1 _ CLIENT_2", names.apply("Anna_Olga"));
        assertEquals("CLIENT_1", names.apply("Ivanova Anna"));
    }

    @Test
    void yoIsTheSameLetterAsYeInNames() {
        NameIndex names = NameIndex.of(Map.of("CLIENT_1", "Семён Зайцев"));
        assertEquals("Звонил CLIENT_1", names.apply("Звонил Семен"));
    }

    @Test
    void urlTailsWithLongPunctuationRunsStayLinear() {
        for (String tail : List.of(".", ",.", "!?", ")")) {
            String text = "https://x.ge/" + tail.repeat(16000 / tail.length()) + "y ";
            long started = System.nanoTime();
            assertEquals("[url] ", mask(text));
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(millis < 1_000, tail + " took " + millis + " ms");
        }
    }

    @Test
    void outputNeverShowsAPhoneByThePlatformRule() {
        String masked = mask("Notes: call 8 (916) 123-45-67 or +995 555 12 34 56 after 18:00");
        assertFalse(PhoneShape.showsPhone(masked, "+79161234567"), masked);
        assertFalse(PhoneShape.showsPhone(masked, "+995555123456"), masked);
        assertTrue(masked.contains("after 18:00"), masked);
    }
}
