package com.gemini.krakenbot.service.impl

/** Centralized Kraken REST API paths, headers, parameter names, and response field identifiers. */
object KrakenApiConstants {
    const val API_URL = "https://api.kraken.com"
    const val API_VERSION = "0"

    const val PATH_BALANCE = "/$API_VERSION/private/Balance"
    const val PATH_TICKER = "/$API_VERSION/public/Ticker"
    const val PATH_ADD_ORDER = "/$API_VERSION/private/AddOrder"
    const val PATH_TRADES_HISTORY = "/$API_VERSION/private/TradesHistory"
    const val PATH_OHLC = "/$API_VERSION/public/OHLC"

    const val HEADER_API_KEY = "API-Key"
    const val HEADER_API_SIGN = "API-Sign"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val CONTENT_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded"

    const val PARAM_PAIR = "pair"
    const val PARAM_TYPE = "type"
    const val PARAM_ORDERTYPE = "ordertype"
    const val PARAM_VOLUME = "volume"
    const val PARAM_NONCE = "nonce"
    const val PARAM_START = "start"
    const val PARAM_OFS = "ofs"
    const val PARAM_CL_ORD_ID = "cl_ord_id"
    const val PARAM_INTERVAL = "interval"
    const val PARAM_SINCE = "since"

    const val FIELD_RESULT = "result"
    const val FIELD_ERROR = "error"
    const val FIELD_COUNT = "count"
    const val FIELD_TRADES = "trades"
    const val FIELD_PAIR = "pair"
    const val FIELD_TYPE = "type"
    const val FIELD_TIME = "time"
    const val FIELD_PRICE = "price"
    const val FIELD_COST = "cost"
    const val FIELD_VOL = "vol"
    const val FIELD_FEE = "fee"
    const val FIELD_LAST = "last"
    const val FIELD_TXID = "txid"
    const val FIELD_ORDER_TXID = "ordertxid"

    const val HMAC_SHA512 = "HmacSHA512"
    const val SHA_256 = "SHA-256"

    const val SUBSTRING_TRADES_HISTORY = "TradesHistory"
    const val SUBSTRING_LEDGERS = "Ledgers"
    const val SUBSTRING_CLOSED_ORDERS = "ClosedOrders"

    const val ERROR_INVALID_NONCE = "Invalid nonce"
    const val ERROR_RATE_LIMIT_EXCEEDED = "Rate limit exceeded"
    const val ERROR_TEMPORARY_LOCKOUT = "Temporary lockout"
    const val ERROR_PUBLIC_API_PREFIX = "Kraken Public API Error: "
    const val ERROR_API_PREFIX = "Kraken API Error: "
    const val ERROR_PARSE_PUBLIC = "Failed to parse public API response"
    const val ERROR_PARSE_PRIVATE = "Failed to parse private API response"
    const val ERROR_API_KEY_NULL = "API Key is null"
}
