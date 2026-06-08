package model

import (
	"testing"

	"github.com/shopspring/decimal"
)

func TestAssetMethods(t *testing.T) {
	btc := Asset("BTC")
	if btc.String() != "BTC" {
		t.Errorf("Expected String() to be BTC, got %s", btc.String())
	}

	if btc.KrakenTicker() != "XBT" {
		t.Errorf("Expected KrakenTicker() for BTC to be XBT, got %s", btc.KrakenTicker())
	}

	doge := Asset("DOGE")
	if doge.KrakenTicker() != "XDG" {
		t.Errorf("Expected KrakenTicker() for DOGE to be XDG, got %s", doge.KrakenTicker())
	}

	eth := Asset("ETH")
	if eth.KrakenTicker() != "ETH" {
		t.Errorf("Expected KrakenTicker() for ETH to be ETH, got %s", eth.KrakenTicker())
	}

	if eth.TradingPair() != "ETHUSD" {
		t.Errorf("Expected TradingPair() for ETH to be ETHUSD, got %s", eth.TradingPair())
	}

	usd := Asset("USD")
	if !usd.IsUSD() {
		t.Errorf("Expected IsUSD() to be true for USD")
	}

	if btc.IsUSD() {
		t.Errorf("Expected IsUSD() to be false for BTC")
	}
}

func TestNewOrderResult(t *testing.T) {
	vol := decimal.NewFromFloat(1.23)
	res := NewOrderResult(true, "XBTUSD", "buy", vol, true, "some error")

	if res.Pair != "XBTUSD" {
		t.Errorf("Expected Pair XBTUSD, got %s", res.Pair)
	}
	if res.Side != "buy" {
		t.Errorf("Expected Side buy, got %s", res.Side)
	}
	if !res.Volume.Equal(vol) {
		t.Errorf("Expected Volume %v, got %v", vol, res.Volume)
	}
	if !res.DryRun {
		t.Errorf("Expected DryRun true")
	}
	if !res.Success {
		t.Errorf("Expected Success true")
	}
	if res.ErrorMessage != "some error" {
		t.Errorf("Expected ErrorMessage 'some error', got '%s'", res.ErrorMessage)
	}
}
