# Distribution suspended

The 0.1.0 APK was withdrawn after identifying exposure of a recovered third-party accessory private key bundled in the APK. Do not redistribute the APK or reuse that identity in new packages.

The public branch has been reset and the old release/tag removed. Restricted local release records and the original repository bundle are preserved for investigation. These steps reduce exposure through project-controlled links; they do not recall existing copies or revoke the credential.

A review of the original published Git objects found no copy of the exposed key in binary or common text encodings, including the private scalar. The original Git tree did contain a different synthetic test key; that file has also been removed. Tests now generate self-signed identities at runtime.

The Android APK-signing key is separate. No evidence of its publication was found in the audited source or release contents. Changing that signing key would not remediate the accessory-key exposure.

Do not include credentials, pairing records or personal information in public issues. Use GitHub private vulnerability reporting for sensitive findings. Credential revocation or replacement must be coordinated with the verified credential owner/provider; this project cannot declare those actions completed.

A future release requires authorized authentication provisioning. Obfuscating an already exposed shared key, encrypting it with a key shipped in the app, or rewriting Git history does not restore confidentiality.

GitHub guidance: https://docs.github.com/en/code-security/tutorials/remediate-leaked-secrets/remediating-a-leaked-secret
