"use strict";
// A CommonJS package, as npm ships many.
Object.defineProperty(exports, "__esModule", { value: true });
exports.shout = exports.version = void 0;
const helper = require("./lib/helper");
const data = require("./data.json");
const tiny = require("tiny-lib");
const fs = require("fs");
const dual = require("tiny-dual");
exports.version = data.version;
exports.shout = (s) => helper.upper(s) + tiny.tiny;
exports.default = helper;
