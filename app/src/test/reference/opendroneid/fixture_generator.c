#include "opendroneid.h"
#include <assert.h>
#include <stdio.h>

static void emit(const char *name, const void *data, size_t size) {
    printf("%s=", name);
    const unsigned char *bytes = data;
    for (size_t i = 0; i < size; i++) printf("%02X", bytes[i]);
    printf("\n");
}

static ODID_Location_encoded location(double lat, double lon, float heading, float speed, float altitude) {
    ODID_Location_data input;
    odid_initLocationData(&input);
    input.Status = ODID_STATUS_AIRBORNE;
    input.Latitude = lat;
    input.Longitude = lon;
    input.Direction = heading;
    input.SpeedHorizontal = speed;
    input.SpeedVertical = 2;
    input.AltitudeGeo = altitude;
    ODID_Location_encoded output;
    assert(encodeLocationMessage(&output, &input) == ODID_SUCCESS);
    return output;
}

static ODID_System_encoded systemMessage(double lat, double lon, int kind, float altitude) {
    ODID_System_data input;
    odid_initSystemData(&input);
    input.OperatorLatitude = lat;
    input.OperatorLongitude = lon;
    input.OperatorLocationType = kind;
    input.OperatorAltitudeGeo = altitude;
    ODID_System_encoded output;
    assert(encodeSystemMessage(&output, &input) == ODID_SUCCESS);
    return output;
}

int main(void) {
    ODID_BasicID_data basic;
    odid_initBasicIDData(&basic);
    basic.IDType = ODID_IDTYPE_SERIAL_NUMBER;
    basic.UAType = ODID_UATYPE_HELICOPTER_OR_MULTIROTOR;
    snprintf(basic.UASID, sizeof(basic.UASID), "1581F3YTDJ1D0031Z530");
    ODID_BasicID_encoded encodedBasic;
    assert(encodeBasicIDMessage(&encodedBasic, &basic) == ODID_SUCCESS);

    ODID_Location_encoded first = location(40, -74, 90, 10, 100);
    ODID_Location_encoded next = location(40.001, -74.002, 135, 12, 101);
    ODID_System_encoded firstSystem = systemMessage(39.9, -73.9, ODID_OPERATOR_LOCATION_TYPE_TAKEOFF, 50);
    ODID_System_encoded nextSystem = systemMessage(39.901, -73.902, ODID_OPERATOR_LOCATION_TYPE_LIVE_GNSS, 55);
    emit("BASIC", &encodedBasic, ODID_MESSAGE_SIZE);
    emit("LOCATION_A", &first, ODID_MESSAGE_SIZE);
    emit("LOCATION_B", &next, ODID_MESSAGE_SIZE);
    emit("SYSTEM_A", &firstSystem, ODID_MESSAGE_SIZE);
    emit("SYSTEM_B", &nextSystem, ODID_MESSAGE_SIZE);

    ODID_MessagePack_data pack;
    odid_initMessagePackData(&pack);
    pack.MsgPackSize = 3;
    pack.Messages[0].basicId = encodedBasic;
    pack.Messages[1].location = first;
    pack.Messages[2].system = firstSystem;
    ODID_MessagePack_encoded encodedPack;
    assert(encodeMessagePack(&encodedPack, &pack) == ODID_SUCCESS);
    emit("PACK_A", &encodedPack, 3 + pack.MsgPackSize * ODID_MESSAGE_SIZE);

    // Exercise the maximum transport size with six independently encoded
    // authentication pages in addition to the Basic ID, Location and System.
    for (int page = 0; page < 6; page++) {
        ODID_Auth_data auth;
        odid_initAuthData(&auth);
        auth.DataPage = page;
        auth.LastPageIndex = 5;
        auth.Length = 17 + 5 * 23;
        assert(encodeAuthMessage(&pack.Messages[3 + page].auth, &auth) == ODID_SUCCESS);
    }
    pack.MsgPackSize = 9;
    assert(encodeMessagePack(&encodedPack, &pack) == ODID_SUCCESS);
    emit("PACK_MAX", &encodedPack, 3 + pack.MsgPackSize * ODID_MESSAGE_SIZE);
}
