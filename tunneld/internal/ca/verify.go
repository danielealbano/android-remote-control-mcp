package ca

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
)

// ConnectAuthContext is the fixed domain-separation label signed with the nonce in the /connect
// possession proof. The phone signs sha256(ConnectAuthContext ‖ nonce).
const ConnectAuthContext = "tunneld-connect-v1"

// ErrNotEC is returned when a certificate's public key is not ECDSA (defense-in-depth vs a
// foreign/legacy cert — never a panic).
var ErrNotEC = errors.New("ca: certificate public key is not ECDSA")

// ParseCertB64DER decodes the base64-DER certificate the phone sends in the AUTH frame.
func ParseCertB64DER(b64 string) (*x509.Certificate, error) {
	der, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("decode base64 cert: %w", err)
	}
	return x509.ParseCertificate(der)
}

// VerifyEnrolledCert verifies the chain against the CA pool and the validity window, returning the
// tunnel name (CN) and the fingerprint.
func VerifyEnrolledCert(cert *x509.Certificate, pool *x509.CertPool) (name, fingerprint string, err error) {
	if _, err := cert.Verify(x509.VerifyOptions{
		Roots:     pool,
		KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageAny},
	}); err != nil {
		return "", "", fmt.Errorf("verify enrolled cert: %w", err)
	}
	name = cert.Subject.CommonName
	if name == "" {
		return "", "", errors.New("ca: enrolled cert has no CN")
	}
	return name, Fingerprint(cert), nil
}

// VerifyPossession verifies the ECDSA-P256 signature over ConnectAuthContext ‖ nonce using the
// cert's public key (the app-layer proof-of-possession). A non-EC key returns ErrNotEC (no panic).
func VerifyPossession(cert *x509.Certificate, nonce, signature []byte) error {
	pub, ok := cert.PublicKey.(*ecdsa.PublicKey)
	if !ok {
		return ErrNotEC
	}
	digest := sha256.Sum256(append([]byte(ConnectAuthContext), nonce...))
	if !ecdsa.VerifyASN1(pub, digest[:], signature) {
		return errors.New("ca: possession signature invalid")
	}
	return nil
}

// Fingerprint returns "sha256:" + hex(sha256(cert.Raw)).
func Fingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	return "sha256:" + hex.EncodeToString(sum[:])
}
