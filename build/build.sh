#!/bin/bash

echo -e "==========================================\n ADB Debloater - Build Script\n==========================================\n"

if ! command -v javac &>/dev/null; then
    echo "ERRORE: javac non trovato. Installa JDK 17+ e aggiungilo al PATH."
    read -p "Premi Invio per uscire..."
    exit 1
fi

mkdir -p build

echo "Compilazione in corso..."
if ! javac --release 17 -d build -encoding UTF-8 Main.java; then
    echo -e "\nERRORE durante la compilazione."
    rm -rf build
    read -p "Premi Invio per uscire..."
    exit 1
fi

echo "Creazione Manifest..."
printf "Manifest-Version: 1.0\nMain-Class: it.debloater.Main\n\n" > build/MANIFEST.MF

echo "Creazione JAR..."
if ! jar cfm ADBDebloater.jar build/MANIFEST.MF -C build .; then
    echo "ERRORE durante la creazione del JAR."
    rm -rf build
    read -p "Premi Invio per uscire..."
    exit 1
fi

echo "Pulizia file temporanei..."
rm -rf build

echo -e "\n==========================================\n BUILD COMPLETATO!\n==========================================\nJAR creato: ADBDebloater.jar\n\nPer eseguire:\n   java -jar ADBDebloater.jar\n"
read -p "Premi Invio per continuare..."