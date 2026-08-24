package com.leonardorozza.mvgrreparacionesbackend.persistence.converter;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class LocaleLegalConverter implements AttributeConverter<LocaleLegal, String> {

    @Override
    public String convertToDatabaseColumn(LocaleLegal attribute) {
        return attribute == null ? null : attribute.getCodigo();
    }

    @Override
    public LocaleLegal convertToEntityAttribute(String dbData) {
        return dbData == null ? null : LocaleLegal.fromCodigo(dbData);
    }
}
