package com.conversive.aep.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EgressPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "10.0.0.1,true", "127.0.0.1,true", "169.254.169.254,true", "100.64.0.1,true", "8.8.8.8,false",
            "fc00::1,true", "2606:4700:4700::1111,false",
            "64:ff9b::7f00:1,true", "64:ff9b::a9fe:a9fe,true", "64:ff9b::808:808,false",
            "2002:7f00:1::1,true", "2002:a9fe:a9fe::1,true", "2002:808:808::1,false"
    })
    void embeddedIpv4InTranslationPrefixesIsJudgedByItsIpv4(String address, boolean internal) throws Exception {
        assertThat(EgressPolicy.isInternal(InetAddress.getByName(address))).isEqualTo(internal);
    }
}
