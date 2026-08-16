// Package ca implements the internal tunnel CA (CSR signing) plus the application-layer /connect
// authentication crypto: parse the phone-sent certificate, verify it against the CA, and verify the
// proof-of-possession signature over the server nonce. Pure crypto; no HTTP.
package ca

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"time"
)

// ErrUnsupportedKeyType is returned by SignCSR for a CSR whose public key is not ECDSA P-256. Only
// P-256 can complete the /connect possession proof, so signing any other key would mint a cert that
// can never authenticate. US8 maps this to 400 unsupported_key_type.
var ErrUnsupportedKeyType = errors.New("ca: unsupported CSR key type (only ECDSA P-256 accepted)")

// CA holds the loaded signing material.
type CA struct {
	cert     *x509.Certificate
	key      crypto.Signer
	validity time.Duration
}

// Load reads the CA certificate + private key (PEM), verifies the certificate is a CA, and returns
// an in-memory signer. Bad/unreadable/non-CA material fails fast.
func Load(certPath, keyPath string, validity time.Duration) (*CA, error) {
	certPEM, err := os.ReadFile(certPath) // #nosec G304 -- operator-configured --ca-cert path (deployment trust boundary, not request-derived)
	if err != nil {
		return nil, fmt.Errorf("read ca cert: %w", err)
	}
	keyPEM, err := os.ReadFile(keyPath) // #nosec G304 -- operator-configured --ca-key path (deployment trust boundary, not request-derived)
	if err != nil {
		return nil, fmt.Errorf("read ca key: %w", err)
	}
	cert, err := parseCertPEM(certPEM)
	if err != nil {
		return nil, fmt.Errorf("parse ca cert: %w", err)
	}
	if !cert.IsCA || !cert.BasicConstraintsValid {
		return nil, errors.New("ca: certificate is not a CA (basicConstraints CA:TRUE required)")
	}
	key, err := parsePrivateKeyPEM(keyPEM)
	if err != nil {
		return nil, fmt.Errorf("parse ca key: %w", err)
	}
	if !publicKeyMatches(cert.PublicKey, key.Public()) {
		return nil, errors.New("ca: private key does not match certificate public key")
	}
	return &CA{cert: cert, key: key, validity: validity}, nil
}

// Pool returns a CA-only pool for verifying enrolled certificates.
func (c *CA) Pool() *x509.CertPool {
	p := x509.NewCertPool()
	p.AddCert(c.cert)
	return p
}

// SignCSR validates the CSR signature, REJECTS a non-ECDSA-P256 key (ErrUnsupportedKeyType), and
// issues a leaf certificate with CN = commonName and --cert-validity lifetime. It ignores ALL
// CSR-provided subject/extensions except the public key (the server sets CN, serial, validity, and
// KeyUsage). Returns the leaf as PEM.
func (c *CA) SignCSR(csrDER []byte, commonName string) (leafPEM []byte, err error) {
	csr, err := x509.ParseCertificateRequest(csrDER)
	if err != nil {
		return nil, fmt.Errorf("parse csr: %w", err)
	}
	if err := csr.CheckSignature(); err != nil {
		return nil, fmt.Errorf("csr signature: %w", err)
	}
	pub, ok := csr.PublicKey.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() {
		return nil, ErrUnsupportedKeyType
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("serial: %w", err)
	}
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: commonName},
		NotBefore:             now.Add(-5 * time.Minute), // small skew tolerance
		NotAfter:              now.Add(c.validity),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  false,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, c.cert, pub, c.key)
	if err != nil {
		return nil, fmt.Errorf("create certificate: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), nil
}

func parseCertPEM(data []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(data)
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, errors.New("no CERTIFICATE PEM block")
	}
	return x509.ParseCertificate(block.Bytes)
}

func parsePrivateKeyPEM(data []byte) (crypto.Signer, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("no PEM block in key")
	}
	if k, err := x509.ParseECPrivateKey(block.Bytes); err == nil {
		return k, nil
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	signer, ok := k.(crypto.Signer)
	if !ok {
		return nil, errors.New("ca key is not a crypto.Signer")
	}
	return signer, nil
}

func publicKeyMatches(a, b any) bool {
	type equalable interface{ Equal(x crypto.PublicKey) bool }
	ea, ok := a.(equalable)
	if !ok {
		return false
	}
	return ea.Equal(b.(crypto.PublicKey))
}
