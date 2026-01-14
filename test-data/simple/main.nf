#!/usr/bin/env nextflow

process HELLO {
    input:
    val greeting

    output:
    val result

    exec:
    result = "Hello, ${greeting}!"
}
