package de.htw_berlin.fb4.mas.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Label {
    private String b64;

    public byte[] getBytes() {
        return b64.getBytes();
    }
}
