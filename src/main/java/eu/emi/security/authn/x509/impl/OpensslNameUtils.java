/*
 * Copyright (c) 2012 ICM Uniwersytet Warszawski All rights reserved.
 * See LICENCE.txt file for licensing information.
 */
package eu.emi.security.authn.x509.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.util.Strings;

import eu.emi.security.authn.x509.helpers.JavaAndBCStyle;

/**
 * This class provides support for the <b>legacy</b> Openssl format of DN encoding. 
 * Please <b>do not use this format unless it is absolutely necessary</b>. It has a number of problems
 * see particular methods documentation for details.
 * 
 * @author K. Benedyczak
 */
public class OpensslNameUtils 
{
	/**
	 * Holds mappings of labels which occur in the wild but are output differently by OpenSSL.
	 * Also useful to have a uniform representation when creating a normalized form.
	 * Note that in some cases OpenSSL doesn't have a label -&gt; then an oid is used.
	 */
	public static final Map<String, String> NORMALIZED_LABELS = new HashMap<String, String>();
	
	static 
	{
		NORMALIZED_LABELS.put("e", "emailAddress");
		NORMALIZED_LABELS.put("email", "emailAddress");
		NORMALIZED_LABELS.put("userid", "UID");
		NORMALIZED_LABELS.put("sn", "serialnumber");
		NORMALIZED_LABELS.put("surname", "sn");
		NORMALIZED_LABELS.put("givenname", "gn");
		NORMALIZED_LABELS.put("dn", "dnQualifier");
		NORMALIZED_LABELS.put("dnq", "dnQualifier");
		NORMALIZED_LABELS.put("uniqueidentifier", "x500UniqueIdentifier");
		NORMALIZED_LABELS.put("generation", "generationQualifier");
		NORMALIZED_LABELS.put("s", "ST");

		NORMALIZED_LABELS.put("ip", "1.3.6.1.4.1.42.2.11.2.1");
		NORMALIZED_LABELS.put("nameatbirth", "1.3.36.8.3.14");
	}
	
	private static String normalizeLabel(String label) 
	{
		String normalized = NORMALIZED_LABELS.get(label.toLowerCase());
		return normalized == null ? label : normalized;
	}
	
	/**
	 * Performs cleaning of the provided openssl legacy DN. The following actions are performed:
	 * <ul>
	 *  <li> all strings of the form '/TOKEN=' are converted to the '/NORMALIZED-TOKEN=',
	 *  where TOKEN and NORMALIZED-TOKEN are taken from the {@link #NORMALIZED_LABELS} map
	 *  <li> the string is converted to lower case
	 * </ul>
	 * Please note that this normalization is far from being perfect: non-ascii characters 
	 * encoded in hex are not lower-cased, it may happen that some tokens are not in the map, 
	 * values containing '/TOKEN=' as a substring will be messed up.
	 * @param legacyDN legacy DN
	 * @return normalized string (hopefully) suitable for the string comparison
	 */
	public static String normalize(String legacyDN)
	{
		Pattern p = Pattern.compile("/[^=]+=");
		Matcher m = p.matcher(legacyDN);
		StringBuilder output = new StringBuilder();
		int i=0;
		while (m.find())
		{
			output.append(legacyDN.substring(i, m.start()));

			String group = m.group();
			String label = group.substring(1, group.length()-1);
			label = normalizeLabel(label);
			
			output.append("/");
			output.append(label);
			output.append("=");
			i=m.end();
		}
		output.append(legacyDN.substring(i, legacyDN.length()));		
		return output.toString().toLowerCase();
	}
	
	/**
	 * @see #opensslToRfc2253(String, boolean) with second arg equal to false
	 * @param inputDN input DN
	 * @return RFC 2253 representation of the input
	 * @deprecated This method is not planned for removal but it is marked as deprecated as it is highly unreliable
	 * and you should update your code not to use openssl style DNs at all
	 * @since 1.1.0
	 */
	@Deprecated
	public static String opensslToRfc2253(String inputDN) 
	{
		return opensslToRfc2253(inputDN, false);
	}
	
	/**
	 * Tries to convert the OpenSSL string representation
	 * of a DN into a RFC 2253 form. The conversion is as follows:
	 * <ol>
	 * <li> the string is split on '/',
	 * <li> all resulting parts which have no '=' sign inside are glued with the previous element
	 * <li> parts are output with ',' as a separator in reversed order.
	 * </ol>
	 * @param inputDN input DN
	 * @param withWildcards whether '*' wildcards need to be recognized
	 * @return RFC 2253 representation of the input
	 * @deprecated This method is not planned for removal but it is marked as deprecated as it is highly unreliable
	 * and you should update your code not to use openssl style DNs at all
	 * @since 1.1.0
	 */
	@Deprecated
	public static String opensslToRfc2253(String inputDN, boolean withWildcards) 
	{
		if (inputDN.length() < 2 || !inputDN.startsWith("/"))
			throw new IllegalArgumentException("The string '" + inputDN +
					"' is not a valid OpenSSL-encoded DN");
		inputDN = inputDN.replace(",", "\\,");
		String[] parts = inputDN.split("/");

		if (parts.length < 2)
			return inputDN.substring(1);

		List<String> avas = new ArrayList<String>();
		avas.add(parts[1]);
		for (int i=2, j=0; i<parts.length; i++)
		{
			if (!(parts[i].contains("=") || (withWildcards && parts[i].contains("*"))))
			{
				String cur = avas.get(j);
				avas.set(j, cur+"/"+parts[i]);
			} else
			{
				avas.add(++j, parts[i]);
			}
		}

		StringBuilder buf = new StringBuilder();
		for (int i=avas.size()-1; i>0; i--)
			buf.append(avas.get(i)).append(",");
		buf.append(avas.get(0));
		return buf.toString();
	}
	
	
	/**
	 * Returns an OpenSSL legacy (and as of now the default in OpenSSL) encoding of the provided RFC 2253 DN. 
	 * Please note that this method is:
	 * <ul>
	 *  <li> written on a best effort basis: OpenSSL format is not documented anywhere.
	 *  <li> it much more problematic to perform an opposite translation as OpenSSL format is highly ambiguous.
	 *  <li> it is <b>STRONGLY</b> suggested not to use this format anywhere, especially in security setups, as 
	 *  many different DNs has the same OpenSSL representation, and also not to use this method.
	 * </ul>
	 * Additionally there is a possibility to turn on the "Globus" compatible mode. In this mode this method
	 * behaves more similarly to the one provided by the COG Jglobus. The basic difference is that RDNs containing 
	 * multiple AVAs are are concatenated with '+' not with '/'.
	 * <p> 
	 * If you want to compare the output of this method (using string comparison) with something 
	 * generated by openssl from a certificate, you can expect problems in case of:
	 * <ul>
	 *  <li>multivalued RDNs: you should sort them, but in OpenSSL format it is even impossible to find them.
	 *  With globusFlavouring turned on it is bit better, but as there is no escaping of special characters
	 *  you are lost too.
	 *  <li>not-so-common attributes used in DN: there is a plenty of attributes which have (or have not) 
	 *  short or long names defined in OpenSSL. This changes over the time in OpenSSL. 
	 *  Also every Globus/gLite/...  tool can use a different set. Therefore whether a correct short name,
	 *  long name or oid is used by this method is also problematic. It is guaranteed that the basic ones 
	 *  (DC, C, OU, O, L, ...) are working. But in case of less common expect troubles (e.g. 
	 *  openssl 1.0.0i uses 'id-pda-countryOfResidence', while this method will output 'CountryOfResidence'). 
	 * </ul> 
	 * @param srcDn input in RFC 2253 format or similar
	 * @param globusFlavouring globus flavouring
	 * @return openssl format encoded input.
	 * @since 1.1.0
	 */
	public static String convertFromRfc2253(String srcDn, boolean globusFlavouring)
	{
		String avasSeparator = globusFlavouring ? "+" : "/";
		
		JavaAndBCStyle style = new JavaAndBCStyle();
		X500Name x500Name = new X500Name(style, srcDn);
		RDN[] rdns = x500Name.getRDNs();
		StringBuilder ret = new StringBuilder();
		
		for (int i=rdns.length-1; i>=0; i--)
		{
			ret.append("/");
			RDN rdn = rdns[i];
			AttributeTypeAndValue[] atvs = rdn.getTypesAndValues();
			for (int j=atvs.length-1; j>=0; j--)
			{
				AttributeTypeAndValue atv = atvs[j];
				ret.append(getShortName4Openssl(atv.getType()));
				ret.append("=");
				ret.append(getOpensslValue(atv.getValue().toASN1Primitive()));
				if (j>0)
					ret.append(avasSeparator);
			}
		}
		return ret.toString();
	}
	
	
	
	private static String getShortName4Openssl(ASN1ObjectIdentifier id)
	{
		JavaAndBCStyle style = new JavaAndBCStyle();
		String name = style.getLabelForOidFull(id);
		if (name == null)
			return id.getId();
		return normalizeLabel(name);
	}

	private static String getOpensslValue(ASN1Object val)
	{
		byte[] bytes;
		if (val instanceof DERBitString)
		{
			bytes = ((DERBitString)val).getBytes();
		} else if (val instanceof ASN1String)
		{
			String valS = ((ASN1String)val).getString();
			char[] chars = valS.toCharArray();
			bytes = Strings.toUTF8ByteArray(chars);
		} else
			throw new IllegalArgumentException("Got AVA value of unsupported type: " + 
					val.getClass().getName());
		
		StringBuilder sb = new StringBuilder();
		for (byte b: bytes)
		{
			if (b <= 0x1f)
			{
				sb.append("\\x" + Integer.toHexString(b & 0xff).toUpperCase());
			} else
				sb.append((char)b);
		}
		return sb.toString();
	}
}
