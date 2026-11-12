using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Pixlink.Services;

/// <summary>The PC's TLS identity: a self-signed certificate created once and kept in the user profile.</summary>
public static class Certificates
{
    private static string PfxPath => Path.Combine(AppSettings.Folder, "pixlink-pc.pfx");

    public static X509Certificate2 LoadOrCreate()
    {
        Directory.CreateDirectory(AppSettings.Folder);
        if (File.Exists(PfxPath))
        {
            // The PFX is protected with DPAPI-backed key storage for the current user.
            return new X509Certificate2(PfxPath, (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
        }

        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest($"CN=Pixlink PC ({Environment.MachineName})", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false)); // server auth

        using var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
        File.WriteAllBytes(PfxPath, cert.Export(X509ContentType.Pfx));
        return new X509Certificate2(PfxPath, (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
    }

    /// <summary>
    /// A client certificate for an iPhone, which can't create its own. Returned as a password-protected
    /// PKCS#12 the phone imports; the PC pins its fingerprint like an Android phone's.
    /// </summary>
    public static (string fingerprint, string p12Base64, string password) CreatePhoneIdentity(string phoneName)
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest($"CN=Pixlink iPhone ({phoneName.Replace(",", " ").Replace("=", " ")})", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.2") }, false)); // client auth
        using var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
        var password = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
        // TripleDES/SHA-1 PKCS#12 is what iOS's SecPKCS12Import reads on every version.
        var pbe = new PbeParameters(PbeEncryptionAlgorithm.TripleDes3KeyPkcs12, HashAlgorithmName.SHA1, 2000);
        var builder = new System.Security.Cryptography.Pkcs.Pkcs12Builder();
        var certBag = new System.Security.Cryptography.Pkcs.Pkcs12SafeContents();
        certBag.AddCertificate(new X509Certificate2(cert.RawData));
        var keyBag = new System.Security.Cryptography.Pkcs.Pkcs12SafeContents();
        keyBag.AddShroudedKey(rsa, password, pbe);
        builder.AddSafeContentsEncrypted(certBag, password, pbe);
        builder.AddSafeContentsUnencrypted(keyBag);
        builder.SealWithMac(password, HashAlgorithmName.SHA1, 2000);
        return (Fingerprint(cert), Convert.ToBase64String(builder.Encode()), password);
    }

    /// <summary>SHA-256 of the certificate in AA:BB:CC form (matches the Android side).</summary>
    public static string Fingerprint(X509Certificate cert) =>
        string.Join(":", SHA256.HashData(cert.GetRawCertData()).Select(b => b.ToString("X2")));
}
