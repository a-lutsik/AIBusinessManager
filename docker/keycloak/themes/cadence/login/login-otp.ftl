<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp'); section>
<!-- template: login-otp.ftl (cadence) -->

    <#if section="header">
        ${msg("doLogIn")}
    <#elseif section="form">
        <form id="kc-otp-login-form" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
            <input id="selectedCredentialId" type="hidden" name="selectedCredentialId" value="${otpLogin.selectedCredentialId!''}">
            <#if otpLogin.userOtpCredentials?size gt 1>
                <div class="${properties.kcFormGroupClass!}">
                    <div class="${properties.kcInputWrapperClass!}">
                        <#list otpLogin.userOtpCredentials as otpCredential>
                            <div id="kc-otp-credential-${otpCredential?index}" class="${properties.kcLoginOTPListClass!}"
                                    onclick="toggleOTP(${otpCredential?index}, '${otpCredential.id}')">
                                <span class="${properties.kcLoginOTPListItemHeaderClass!}">
                                    <span class="${properties.kcLoginOTPListItemIconBodyClass!}">
                                      <i class="${properties.kcLoginOTPListItemIconClass!}" aria-hidden="true"></i>
                                    </span>
                                    <span class="${properties.kcLoginOTPListItemTitleClass!}">${otpCredential.userLabel}</span>
                                </span>
                            </div>
                        </#list>
                    </div>
                </div>
            </#if>

            <@field.group name="otp" label=msg("loginOtpOneTime") error=kcSanitize(messagesPerField.get('totp'))?no_esc>
                <div class="cadence-otp-boxes" data-cadence-otp>
                    <#list 0..5 as i>
                        <input type="text" inputmode="numeric" pattern="[0-9]*" maxlength="1" class="cadence-otp-box" autocomplete="one-time-code"<#if i==0> autofocus</#if> />
                    </#list>
                </div>
                <input type="hidden" id="otp" name="otp" value="" />
            </@field.group>

            <@buttons.loginButton />
        </form>
        <script>
            function toggleOTP(index, value) {
                document.getElementById("selectedCredentialId").value = value;
                Array.from(document.getElementsByClassName("${properties.kcLoginOTPListSelectedClass!}")).map(i => i.classList.remove("${properties.kcLoginOTPListSelectedClass!}"));
                document.getElementById("kc-otp-credential-" + index).classList.add("${properties.kcLoginOTPListSelectedClass!}");
            }
        </script>
        <script>
          (function() {
            var wrap = document.querySelector('[data-cadence-otp]');
            if (!wrap) return;
            var boxes = Array.prototype.slice.call(wrap.querySelectorAll('.cadence-otp-box'));
            var hidden = document.getElementById('otp');
            function sync() { hidden.value = boxes.map(function(b) { return b.value; }).join(''); }
            boxes.forEach(function(box, idx) {
              box.addEventListener('input', function() {
                box.value = box.value.replace(/[^0-9]/g, '').slice(0, 1);
                if (box.value && boxes[idx + 1]) boxes[idx + 1].focus();
                sync();
              });
              box.addEventListener('keydown', function(e) {
                if (e.key === 'Backspace' && !box.value && boxes[idx - 1]) boxes[idx - 1].focus();
              });
              box.addEventListener('paste', function(e) {
                var text = (e.clipboardData || window.clipboardData).getData('text').replace(/[^0-9]/g, '');
                if (!text) return;
                e.preventDefault();
                text.split('').slice(0, boxes.length).forEach(function(ch, i2) { if (boxes[i2]) boxes[i2].value = ch; });
                sync();
                var next = boxes[Math.min(text.length, boxes.length - 1)];
                if (next) next.focus();
              });
            });
          })();
        </script>
    </#if>
</@layout.registrationLayout>
