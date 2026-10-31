package com.cadence.ai.internal.privacy;

import com.cadence.ai.internal.privacy.OwnerMessageMasker.Person;
import com.cadence.platform.phone.PhoneShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-style check (stage 05 T3): for a fixture of client and master names, phones and emails, every combination
 * of them in message and note templates is masked so that no name word, phone (by the platform {@link PhoneShape}
 * rule, any formatting) or email of the fixture appears in any masker output.
 */
class MaskingLeakPropertyTest {

    private static final List<String> CLIENT_NAMES = List.of(
            "Anna Ivanova", "Anna Petrova", "Maria Sokolova", "Олег Смирнов", "Ли Ян", "Семён Зайцев", "Тетяна Коваленко", "Jana Nováková",
            "გიორგი მელაძე", "Nino Kvaratskhelia");
    private static final List<String> MASTER_NAMES = List.of("Nino Beridze", "Tamar Lomidze", "Дарья Волкова");
    private static final List<String> PHONES = List.of(
            "+995 555 12 34 56", "8 (916) 123-45-67", "+420 777 123 456", "555-1234567", "+79161234567",
            "555123456", "+380 (44) 123 45 67", "777.123.456", "+7\u00A0916\u00A0123\u00A045\u00A067",
            "+7 916 - 123 - 45 - 67", "916   123   45   67", "916/123/45/67", "+7 916,123,45,67", "+7 916_123_45_67",
            "+7 ( 916 ) 123 45 67", "+7 916 123\u200B45\u200B67", "+995 555 12.03.56", "+7 916 1234-12-12",
            "(495) 1234-12-12", "8 916 12-12-2026", "+995 55 12.03.56", "06 12.10.20", "77 12.10.2026", "916) 123-45-67",
            "[916] 123 45 67", "916\u2212123\u221245\u221267", "916\u00B7123\u00B745\u00B767", "916~123~45~67", "916|123|45|67",
            "916\n123\n45\n67", "916---123---45---67", "916, 123, 45, 67");
    private static final List<String> EMAILS = List.of(
            "anna.ivanova@gmail.com", "OLEG_S@mail.ru", "jana+salon@seznam.cz", "nino.b@salon.ge",
            "anna.o'brien@gmail.com", "petr.nova\u0301k@seznam.cz", "tamar(at)mail.ru");
    private static final List<String> MESSAGE_TEMPLATES = List.of(
            "Move %s to Friday, phone %s, mail %s",
            "%s; %s; %s",
            "Почему %s не пришла? Телефон: %s. Почта %s.",
            "%s (%s, %s) wants 10:30-11:15 on 27.10",
            "Перенеси %s\n%s\n%s",
            "%s,%s,%s");

    private final FreeTextMasker freeText = new FreeTextMasker();

    @Test
    void noFixtureNamePhoneOrEmailSurvivesAnyMasker() {
        UUID convo = UUID.randomUUID();
        PseudonymRegistry registry = new PseudonymRegistry(new InMemoryPseudonymStore());
        List<Person> clients = CLIENT_NAMES.stream().map(n -> new Person(UUID.randomUUID(), n)).toList();
        List<Person> masters = MASTER_NAMES.stream().map(n -> new Person(UUID.randomUUID(), n)).toList();
        OwnerMessageMasker owner = new OwnerMessageMasker(() -> masters, words -> clients.stream()
                .filter(p -> !Collections.disjoint(Words.nameWords(p.displayName()), words))
                .toList(), registry, freeText);

        List<String> names = new ArrayList<>(CLIENT_NAMES);
        names.addAll(MASTER_NAMES);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String template : MESSAGE_TEMPLATES) {
            for (String name : names) {
                for (String phone : PHONES) {
                    for (String email : EMAILS) {
                        String text = String.format(template, name, phone, email);
                        String ownerMasked = owner.mask(convo, text);
                        checked += check("owner", text, ownerMasked, name, phone, email, failures);
                    }
                }
            }
        }
        // Notes: by then every fixture person is a ref of the conversation (the owner messages above named them).
        NameIndex known = registry.nameIndex(convo);
        assertEquals(names.size(), known.size(), "every fixture person has a ref");
        for (String template : MESSAGE_TEMPLATES) {
            for (String name : names) {
                for (String phone : PHONES) {
                    for (String email : EMAILS) {
                        String text = String.format(template, name, phone, email);
                        checked += check("note", text, freeText.mask(text, known), name, phone, email, failures);
                        checked += check("note-lower", text,
                                freeText.mask(text.toLowerCase(Locale.ROOT), known), name, phone, email, failures);
                    }
                }
            }
        }
        assertEquals(List.of(), failures.stream().limit(20).toList(), "leaks out of " + checked + " outputs");
    }

    private static int check(String masker, String in, String out, String name, String phone, String email,
                             List<String> failures) {
        String lower = out.toLowerCase(Locale.ROOT);
        for (String word : name.split(" ")) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                failures.add(masker + " leaks name '" + word + "': " + in + " -> " + out);
            }
        }
        if (PhoneShape.showsPhone(out, phone)) {
            failures.add(masker + " leaks phone: " + in + " -> " + out);
        }
        if (lower.contains(email.toLowerCase(Locale.ROOT)) || lower.contains(email.split("@")[0].toLowerCase(Locale.ROOT))) {
            failures.add(masker + " leaks email: " + in + " -> " + out);
        }
        return 1;
    }
}
