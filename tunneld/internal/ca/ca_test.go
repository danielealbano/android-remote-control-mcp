package ca

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"regexp"
	"testing"
	"time"
)

const testValidity = 10 * 365 * 24 * time.Hour

func genCAMaterial(t *testing.T, isCA bool) ([]byte, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "tunneld-ca-test"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(testValidity),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  isCA,
		MaxPathLenZero:        isCA, // MaxPathLen is only valid on a CA cert
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return der, key
}

func writeCAFiles(t *testing.T, certDER []byte, key *ecdsa.PrivateKey) (certPath, keyPath string) {
	t.Helper()
	dir := t.TempDir()
	certPath = filepath.Join(dir, "ca.pem")
	keyPath = filepath.Join(dir, "ca-key.pem")
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	return certPath, keyPath
}

func newTestCA(t *testing.T) *CA {
	t.Helper()
	der, key := genCAMaterial(t, true)
	cp, kp := writeCAFiles(t, der, key)
	ca, err := Load(cp, kp, testValidity)
	if err != nil {
		t.Fatal(err)
	}
	return ca
}

func p256Key(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func makeCSR(t *testing.T, key crypto.Signer) []byte {
	t.Helper()
	tmpl := &x509.CertificateRequest{
		Subject:  pkix.Name{CommonName: "attacker-controlled-cn"},
		DNSNames: []string{"evil.example.test"},
	}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, key)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

// signLeaf issues a leaf cert directly with the CA material (white-box) so tests can set arbitrary
// validity windows (expired / not-yet-valid).
func signLeaf(t *testing.T, ca *CA, notBefore, notAfter time.Time, cn string, pub crypto.PublicKey) *x509.Certificate {
	t.Helper()
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: cn},
		NotBefore:             notBefore,
		NotAfter:              notAfter,
		KeyUsage:              x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.cert, pub, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func TestLoadRejectsBadCAMaterial(t *testing.T) {
	// Missing files.
	if _, err := Load("/nope/ca.pem", "/nope/ca-key.pem", testValidity); err == nil {
		t.Error("missing files must error")
	}
	// Corrupt cert.
	dir := t.TempDir()
	badCert := filepath.Join(dir, "bad.pem")
	_ = os.WriteFile(badCert, []byte("not a pem"), 0o600)
	_, key := genCAMaterial(t, true)
	_, keyPath := writeCAFiles(t, mustDER(t, key, true), key)
	if _, err := Load(badCert, keyPath, testValidity); err == nil {
		t.Error("corrupt cert must error")
	}
	// Non-CA cert.
	der, k := genCAMaterial(t, false)
	cp, kp := writeCAFiles(t, der, k)
	if _, err := Load(cp, kp, testValidity); err == nil {
		t.Error("non-CA cert must error")
	}
}

// mustDER re-issues a CA cert DER for the given key (helper for the corrupt-cert case).
func mustDER(t *testing.T, key *ecdsa.PrivateKey, isCA bool) []byte {
	t.Helper()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "x"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(testValidity),
		KeyUsage: x509.KeyUsageCertSign, BasicConstraintsValid: true, IsCA: isCA,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func TestSignThenVerifyRoundTrips(t *testing.T) {
	ca := newTestCA(t)
	key := p256Key(t)
	leafPEM, err := ca.SignCSR(makeCSR(t, key), "abc2345678")
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode(leafPEM)
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	name, fp, err := VerifyEnrolledCert(cert, ca.Pool())
	if err != nil {
		t.Fatal(err)
	}
	if name != "abc2345678" {
		t.Errorf("CN = %q, want abc2345678", name)
	}
	if fp == "" {
		t.Error("fingerprint empty")
	}
}

func TestSignRejectsBadCSRSignature(t *testing.T) {
	ca := newTestCA(t)
	csr := makeCSR(t, p256Key(t))
	csr[len(csr)-1] ^= 0xff // tamper the signature tail
	if _, err := ca.SignCSR(csr, "name123456"); err == nil {
		t.Error("tampered CSR must be rejected")
	}
}

func TestSignIgnoresCSRSubjectExtensions(t *testing.T) {
	ca := newTestCA(t)
	leafPEM, err := ca.SignCSR(makeCSR(t, p256Key(t)), "servername")
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode(leafPEM)
	cert, _ := x509.ParseCertificate(block.Bytes)
	if cert.Subject.CommonName != "servername" {
		t.Errorf("CN = %q, want servername (CSR CN discarded)", cert.Subject.CommonName)
	}
	if len(cert.DNSNames) != 0 {
		t.Errorf("SANs = %v, want none (CSR SAN discarded)", cert.DNSNames)
	}
}

func TestSignRejectsNonP256Key(t *testing.T) {
	ca := newTestCA(t)
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	if _, err := ca.SignCSR(makeCSR(t, rsaKey), "n123456789"); err != ErrUnsupportedKeyType {
		t.Errorf("RSA CSR: err = %v, want ErrUnsupportedKeyType", err)
	}
	p384, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if _, err := ca.SignCSR(makeCSR(t, p384), "n123456789"); err != ErrUnsupportedKeyType {
		t.Errorf("P-384 CSR: err = %v, want ErrUnsupportedKeyType", err)
	}
}

func TestPossessionRejectsNonECCert(t *testing.T) {
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "rsa"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &rsaKey.PublicKey, rsaKey)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	if err := VerifyPossession(cert, []byte("nonce"), []byte("sig")); err != ErrNotEC {
		t.Errorf("RSA cert possession: err = %v, want ErrNotEC", err)
	}
}

func TestParseB64DERRoundTrips(t *testing.T) {
	ca := newTestCA(t)
	leafPEM, _ := ca.SignCSR(makeCSR(t, p256Key(t)), "roundtrip1")
	block, _ := pem.Decode(leafPEM)
	b64 := base64.StdEncoding.EncodeToString(block.Bytes)
	cert, err := ParseCertB64DER(b64)
	if err != nil {
		t.Fatal(err)
	}
	if string(cert.Raw) != string(block.Bytes) {
		t.Error("round-tripped cert differs")
	}
	if _, err := ParseCertB64DER("!!!not base64!!!"); err == nil {
		t.Error("garbage b64 must error")
	}
}

func TestVerifyRejectsUnknownCA(t *testing.T) {
	ca1 := newTestCA(t)
	ca2 := newTestCA(t)
	leaf := signLeaf(t, ca2, time.Now().Add(-time.Hour), time.Now().Add(time.Hour), "cross", &p256Key(t).PublicKey)
	if _, _, err := VerifyEnrolledCert(leaf, ca1.Pool()); err == nil {
		t.Error("cert from another CA must not verify")
	}
}

func TestVerifyRejectsExpiredCert(t *testing.T) {
	ca := newTestCA(t)
	leaf := signLeaf(t, ca, time.Now().Add(-2*time.Hour), time.Now().Add(-time.Hour), "expired", &p256Key(t).PublicKey)
	if _, _, err := VerifyEnrolledCert(leaf, ca.Pool()); err == nil {
		t.Error("expired cert must not verify")
	}
}

func TestVerifyRejectsNotYetValidCert(t *testing.T) {
	ca := newTestCA(t)
	leaf := signLeaf(t, ca, time.Now().Add(time.Hour), time.Now().Add(2*time.Hour), "future", &p256Key(t).PublicKey)
	if _, _, err := VerifyEnrolledCert(leaf, ca.Pool()); err == nil {
		t.Error("not-yet-valid cert must not verify")
	}
}

func TestPossessionSignatureRoundTrips(t *testing.T) {
	ca := newTestCA(t)
	key := p256Key(t)
	leafPEM, _ := ca.SignCSR(makeCSR(t, key), "possession1")
	block, _ := pem.Decode(leafPEM)
	cert, _ := x509.ParseCertificate(block.Bytes)

	nonce := []byte("a-server-chosen-random-nonce-32b")
	digest := sha256.Sum256(append([]byte(ConnectAuthContext), nonce...))
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	if err := VerifyPossession(cert, nonce, sig); err != nil {
		t.Fatalf("valid possession signature rejected: %v", err)
	}
	// Wrong key.
	otherKey := p256Key(t)
	digest2 := sha256.Sum256(append([]byte(ConnectAuthContext), nonce...))
	badSig, _ := ecdsa.SignASN1(rand.Reader, otherKey, digest2[:])
	if err := VerifyPossession(cert, nonce, badSig); err == nil {
		t.Error("signature by a different key must fail")
	}
	// Wrong/stale nonce.
	otherNonce := []byte("a-DIFFERENT-server-nonce-value!!")
	if err := VerifyPossession(cert, otherNonce, sig); err == nil {
		t.Error("signature over a different nonce must fail (no replay)")
	}
}

func TestFingerprintStableAndPrefixed(t *testing.T) {
	ca := newTestCA(t)
	leafPEM, _ := ca.SignCSR(makeCSR(t, p256Key(t)), "fp12345678")
	block, _ := pem.Decode(leafPEM)
	cert, _ := x509.ParseCertificate(block.Bytes)
	f1 := Fingerprint(cert)
	f2 := Fingerprint(cert)
	if f1 != f2 {
		t.Error("fingerprint not stable")
	}
	if len(f1) != len("sha256:")+64 || f1[:7] != "sha256:" {
		t.Errorf("fingerprint shape wrong: %q", f1)
	}
}

func TestGenerateNameShapeAndReservedSkip(t *testing.T) {
	re := regexp.MustCompile(`^[a-z2-7]{10}$`)
	for i := 0; i < 200; i++ {
		name, err := GenerateName("", 10)
		if err != nil {
			t.Fatal(err)
		}
		if !re.MatchString(name) {
			t.Fatalf("name %q not 10 chars of [a-z2-7]", name)
		}
		if _, bad := reserved[name]; bad {
			t.Fatalf("generated a reserved name %q", name)
		}
	}
}
