// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <iostream>

#include "common/status.h"

// This files contains some utilities to convert Doris internal
// data format into/from Apache Arrow format. When Doris needs
// to share data with others we can leverage Arrow's ability.
// We can convert our internal data into Arrow's memory format
// and then convert to other format, for example Parquet. Because
// Arrow have already implemented this functions. And what's more
// Arrow support multiple languages, so it will make it easy to
// handle Doris data in other languages.

// Forward declaration of arrow class
namespace arrow {
class Array;
class RecordBatch;
class Status;
} // namespace arrow

namespace doris {

#define RETURN_ARROW_STATUS_IF_ERROR(stmt)                  \
    do {                                                    \
        Status _status_ = (stmt);                           \
        if (UNLIKELY(!_status_.ok())) {                     \
            ARROW_RETURN_NOT_OK(to_arrow_status(_status_)); \
        }                                                   \
    } while (false)

#define RETURN_DORIS_STATUS_IF_ERROR(stmt)    \
    do {                                      \
        arrow::Status _status_ = (stmt);      \
        if (UNLIKELY(!_status_.ok())) {       \
            return to_doris_status(_status_); \
        }                                     \
    } while (false)

#define RETURN_ARROW_STATUS_IF_CATCH_EXCEPTION(stmt)                               \
    do {                                                                           \
        try {                                                                      \
            arrow::Status _status_ = (stmt);                                       \
            return _status_;                                                       \
        } catch (const doris::Exception& e) {                                      \
            return to_arrow_status(Status::Error<false>(e.code(), e.to_string())); \
        }                                                                          \
    } while (0)

// Pretty print a arrow RecordBatch.
Status arrow_pretty_print(const arrow::RecordBatch& rb, std::ostream* os);
Status arrow_pretty_print(const arrow::Array& rb, std::ostream* os);

Status to_doris_status(const arrow::Status& status);
arrow::Status to_arrow_status(const Status& status);

} // namespace doris
