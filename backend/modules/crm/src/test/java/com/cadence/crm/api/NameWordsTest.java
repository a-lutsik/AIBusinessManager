package com.cadence.crm.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameWordsTest {

    private static boolean matches(String typed, String stored) {
        return NameWords.matches(NameWords.key(typed), NameWords.key(stored), NameWords.capitalised(typed));
    }

    @ParameterizedTest
    @CsvSource({
            "Анну, Анна", "Анной, Анна", "Анне, Анна", "Петрову, Петрова", "Петровой, Петрова", "петрову, Петрова", "Петровым, Петрова",
            "Марии, Мария", "Марию, Мария", "Марией, Мария", "Ивану, Иван", "Иваном, Иван", "Ивана, Иван", "Иванова, Иванов", "Иванов, Иванова",
            "Novákové, Nováková", "Novákovou, Nováková", "Novakove, Nováková", "Petra, Petr", "Petrovi, Petr", "Novákem, Novák",
            "Олени, Олена", "Сергею, Сергей", "Шевченка, Шевченко", "Игоря, Игорь", "Ильи, Илья", "Стоцкого, Стоцкий",
            "Anna, Anna", "Ли, ли", "Семён, Семен", "Мар'яну, Мар'яна", "мар’яну, Мар'яна", "მელაძეს, მელაძე", "Annas, Anna"
    })
    void formsOfOneNameMatch(String typed, String stored) {
        assertTrue(matches(typed, stored), typed + "/" + stored);
    }

    /** The typed ending may begin with the stored name's last letters: the stem is shorter than the common prefix. */
    @ParameterizedTest
    @CsvSource({
            "Шевченком, Шевченко", "Шевченкові, Шевченко", "Дмитром, Дмитро", "Петром, Петро", "Павлові, Павло",
            "Стоцким, Стоцкий", "Достоевским, Достоевский", "Толстого, Толстой", "Толстому, Толстой", "Толстом, Толстой",
            "Novotným, Novotný"
    })
    void anEndingMayBeginWithTheLastLettersOfTheStoredName(String typed, String stored) {
        assertTrue(matches(typed, stored), typed + "/" + stored);
    }

    @ParameterizedTest
    @CsvSource({
            "Петрової, Петрова", "Іванової, Іванова", "Сергієві, Сергій", "Андрієві, Андрій",
            "Jiřího, Jiří", "Jiřímu, Jiří", "Jiřím, Jiří", "Krejčího, Krejčí", "Krejčím, Krejčí",
            "ანასთან, ანა", "გიორგისგან, გიორგი", "დავითისთვის, დავითი"
    })
    void endingsOfUkrainianCzechAndGeorgian(String typed, String stored) {
        assertTrue(matches(typed, stored), typed + "/" + stored);
    }

    @ParameterizedTest
    @CsvSource({"Олю, Оля", "Юлей, Юля", "Еву, Ева", "Ире, Ира", "Яну, Яна", "Ани, Аня", "Ольга, Ольга", "ანას, ანა", "ნიკას, ნიკა"})
    void aShortNameMatchesThroughATwoLetterStemWhenCapitalised(String typed, String stored) {
        assertTrue(matches(typed, stored), typed + "/" + stored);
    }

    @ParameterizedTest
    @CsvSource({"олю, Оля", "юлей, Юля", "еву, Ева", "ире, Ира", "яну, Яна", "ани, Аня"})
    void aShortNameStaysExactWhenTypedInLowerCase(String typed, String stored) {
        assertFalse(matches(typed, stored), typed + "/" + stored);
    }

    @ParameterizedTest
    @CsvSource({
            "верно, Вера", "лень, Лена", "market, Mark", "завтрак, Завтра", "Завтрак, Завтра", "дано, Дана", "Дано, Дана", "маринад, Марина",
            "Мария, Марта", "Анкета, Анна", "Ива, Иван", "Иван, Ива", "Льва, Лев", "Павла, Павел", "Ли, Лия", "Annabel, Anna", "Ann, Anna",
            "Ян, Яна", "Яна, Ян", "Олег, Оля", "Ольга, Оля", "Петр, Петрова", "Petra, Petrova", "Иван, Иванов", "Как, Кая", "Ирак, Ира", "Анна, Ана", "Яно, Яна"
    })
    void otherWordsDoNot(String typed, String stored) {
        assertFalse(matches(typed, stored), typed + "/" + stored);
    }

    @Test
    void anOrdinaryWordWithARealEndingIsAnAcceptedFalsePositive() {
        assertTrue(matches("верю", "Вера"));
    }

    /** The prefilter is never narrower: every accepted pair is found through its stems and endings. */
    @Test
    void stemsCoverEveryAcceptedPair() {
        for (String[] pair : new String[][] {
                {"Анну", "Анна"}, {"Петровой", "Петрова"}, {"Иваном", "Иван"}, {"Иван", "Иваном"}, {"Олю", "Оля"}, {"Шевченком", "Шевченко"}, {"Стоцким", "Стоцкий"}, {"Толстого", "Толстой"},
                {"Novotným", "Novotný"}, {"Jiřího", "Jiří"}, {"ანასთან", "ანა"}, {"Сергієві", "Сергій"}, {"Шевченка", "Шевченко"},
                {"Игоря", "Игорь"}, {"Novákové", "Nováková"}, {"ანას", "ანა"}, {"Марии", "Мария"}, {"Мар'яну", "Мар'яна"}}) {
            String typed = NameWords.key(pair[0]);
            String stored = NameWords.key(pair[1]);
            assertTrue(NameWords.matches(typed, stored, true), pair[0]);
            boolean found = NameWords.stems(typed).contains(stored) || NameWords.storedEndings().stream()
                    .anyMatch(e -> stored.endsWith(e) && NameWords.stems(typed).contains(stored.substring(0, stored.length() - e.length())));
            assertTrue(found, pair[0] + "/" + pair[1]);
        }
    }

    @Test
    void keyFoldsDottedISigmaAccentsAndComposition() {
        assertEquals("ilham", NameWords.key("İlham"));
        assertEquals("ilham", NameWords.key("Ilham"));
        assertEquals("ilham", NameWords.key("ılham"));
        assertEquals("σωσ", NameWords.key("ΣΩΣ"));
        assertEquals("σωσ", NameWords.key("σως"));
        assertEquals("анна", NameWords.key("Анна"));
        assertEquals("анна", NameWords.key("А́нна"));
        assertEquals("семен", NameWords.key("СЕМЁН"));
        assertEquals("семен", NameWords.key("Семён"));
        assertEquals("jose", NameWords.key("José"));
        assertEquals("მელაძე", NameWords.key("მელაძე"));
        assertEquals("марʼяна".replace("ʼ", ""), NameWords.key("Мар'яна"));
        assertEquals(NameWords.key("Мар’яна"), NameWords.key("Мар'яна"));
    }

    @Test
    void lettersAreCodePoints() {
        assertEquals(4, NameWords.letters("𝒜bcd"));
    }
}
