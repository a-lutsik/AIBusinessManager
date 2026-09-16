package com.cadence.platform.phone;

import com.cadence.platform.error.DomainException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public String toE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            throw DomainException.badRequest("INVALID_PHONE", "Phone is required");
        }
        try {
            Phonenumber.PhoneNumber parsed = util.parse(raw, defaultRegion);
            if (!util.isValidNumber(parsed)) {
                throw DomainException.badRequest("INVALID_PHONE", "Phone is not a valid number");
            }
            return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw DomainException.badRequest("INVALID_PHONE", "Phone could not be parsed");
        }
    }
}
